/**********************************************************************
 *
 * Copyright (c) 2026 3MF
 * All rights reserved.
 *
 * This software is copyrighted work licensed under the terms of the
 * Jameica License.  Please consult the file "LICENSE" for details.
 *
 **********************************************************************/

package de.willuhn.jameica.hbci.payment;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.kapott.hbci.GV_Result.GVRVoP.VoPResult;
import org.kapott.hbci.GV_Result.GVRVoP.VoPResultItem;
import org.kapott.hbci.GV_Result.GVRVoP.VoPStatus;

/**
 * Haelt die offene VoP-Entscheidung, die im Web-Frontend (freigabe.html) getroffen wird.
 * Der HBCI-Callback wartet blockierend, bis entschieden wurde oder die Wartezeit ablaeuft.
 * Ohne Entscheidung gilt der Auftrag als abgelehnt.
 */
public class Freigaben
{
  /**
   * Eine offene Entscheidung.
   */
  public static class Entscheidung
  {
    private final String id = UUID.randomUUID().toString();
    private final Date created = new Date();
    private final VoPResult result;
    private final String text;
    private final String auftrag;
    private final Date deadline;
    private Boolean approved = null;

    private Entscheidung(VoPResult result, String text, String auftrag, int seconds)
    {
      this.result   = result;
      this.text     = text;
      this.auftrag  = auftrag;
      this.deadline = new Date(this.created.getTime() + seconds * 1000L);
    }

    /**
     * @return die ID der Entscheidung.
     */
    public String getId()
    {
      return this.id;
    }

    /**
     * @return Zeitpunkt, zu dem die Entscheidung angelegt wurde.
     */
    public Date getCreated()
    {
      return this.created;
    }

    /**
     * @return Zeitpunkt, zu dem der Auftrag ohne Entscheidung abgelehnt wird.
     */
    public Date getDeadline()
    {
      return this.deadline;
    }

    /**
     * @return der Informationstext der Bank.
     */
    public String getText()
    {
      return this.text;
    }

    /**
     * @return Beschreibung des zugehoerigen Auftrages, insofern ermittelbar.
     */
    public String getAuftrag()
    {
      return this.auftrag;
    }

    /**
     * @return die Ergebnisse des Namensabgleichs.
     */
    public List<VoPResultItem> getItems()
    {
      return this.result.getItems();
    }
  }

  private static Entscheidung pending = null;

  /**
   * Prueft, ob alle Ergebnisse "nicht anwendbar" (RVNA) sind.
   * @param result das VoP-Ergebnis.
   * @return true, wenn mindestens ein Ergebnis existiert und alle RVNA sind.
   */
  public static boolean isNotApplicable(VoPResult result)
  {
    if (result == null || result.getItems() == null || result.getItems().isEmpty())
      return false;
    return result.getItems().stream().allMatch(i -> i.getStatus() == VoPStatus.NOT_APPLICABLE);
  }

  /**
   * Legt eine offene Entscheidung an und wartet auf sie.
   * @param result das VoP-Ergebnis.
   * @param text der Informationstext der Bank.
   * @param auftrag Beschreibung des Auftrages.
   * @param seconds maximale Wartezeit in Sekunden.
   * @return true nur dann, wenn im Web-Frontend explizit freigegeben wurde.
   */
  public static boolean await(VoPResult result, String text, String auftrag, int seconds)
  {
    final Entscheidung e = new Entscheidung(result,text,auftrag,seconds);
    synchronized (Freigaben.class)
    {
      pending = e;
    }

    try
    {
      synchronized (e)
      {
        while (e.approved == null)
        {
          long left = e.deadline.getTime() - System.currentTimeMillis();
          if (left <= 0)
            break;
          e.wait(left);
        }
        return Boolean.TRUE.equals(e.approved);
      }
    }
    catch (InterruptedException ie)
    {
      Thread.currentThread().interrupt();
      return false;
    }
    finally
    {
      synchronized (Freigaben.class)
      {
        if (pending == e)
          pending = null;
      }
    }
  }

  /**
   * @return die aktuell offene Entscheidung oder null.
   */
  public static synchronized Entscheidung getPending()
  {
    return pending;
  }

  /**
   * Trifft die Entscheidung.
   * @param id die ID der Entscheidung.
   * @param approve true, wenn der Auftrag ausgefuehrt werden soll.
   * @return true, wenn die Entscheidung noch offen war und uebernommen wurde.
   */
  public static boolean decide(String id, boolean approve)
  {
    final Entscheidung e = getPending();
    if (e == null || id == null || !e.id.equals(id))
      return false;

    synchronized (e)
    {
      if (e.approved != null)
        return false;
      e.approved = approve;
      e.notifyAll();
      return true;
    }
  }
}
