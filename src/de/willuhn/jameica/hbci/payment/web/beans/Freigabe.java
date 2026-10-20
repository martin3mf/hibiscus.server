/**********************************************************************
 *
 * Copyright (c) 2026 3MF
 * All rights reserved.
 *
 * This software is copyrighted work licensed under the terms of the
 * Jameica License.  Please consult the file "LICENSE" for details.
 *
 **********************************************************************/

package de.willuhn.jameica.hbci.payment.web.beans;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import de.willuhn.datasource.pseudo.PseudoIterator;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.payment.Freigaben;
import de.willuhn.jameica.hbci.payment.Plugin;
import de.willuhn.jameica.hbci.rmi.AuslandsUeberweisung;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobSepaUeberweisung;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.services.BeanService;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.webadmin.annotation.Request;
import de.willuhn.jameica.webadmin.annotation.Response;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Controller fuer die manuelle Ausfuehrung von SEPA-Ueberweisungen und die VoP-Freigabe.
 */
public class Freigabe
{
  private final static I18N i18n = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getI18N();
  private final static String TOKEN = "freigabe.token";

  @Request
  private HttpServletRequest request = null;

  @Response
  private HttpServletResponse response = null;

  /**
   * Liefert alle noch nicht ausgefuehrten SEPA-Ueberweisungen.
   * @return Liste der offenen Ueberweisungen.
   * @throws Exception
   */
  public List<AuslandsUeberweisung> getUeberweisungen() throws Exception
  {
    DBIterator list = Settings.getDBService().createList(AuslandsUeberweisung.class);
    list.addFilter("(ausgefuehrt is null or ausgefuehrt = 0)");
    list.setOrder("order by termin, id");
    return PseudoIterator.asList(list);
  }

  /**
   * @return die offene VoP-Entscheidung oder null.
   */
  public Freigaben.Entscheidung getPending()
  {
    return Freigaben.getPending();
  }

  /**
   * @return true, wenn gerade eine Synchronisierung laeuft.
   */
  public boolean isRunning()
  {
    return new Welcome().isRunning();
  }

  /**
   * Liefert das CSRF-Token der Session.
   * @return das Token.
   */
  public String getToken()
  {
    final HttpSession session = this.request.getSession(true);
    String token = (String) session.getAttribute(TOKEN);
    if (token == null)
    {
      token = new BigInteger(130,new SecureRandom()).toString(32);
      session.setAttribute(TOKEN,token);
    }
    return token;
  }

  /**
   * Fuehrt die ausgewaehlten faelligen Ueberweisungen aus.
   * @throws Exception
   */
  public void execute() throws Exception
  {
    try
    {
      this.checkRequest();

      final String[] ids = this.request.getParameterValues("id");
      if (ids == null || ids.length == 0)
        throw new ApplicationException(i18n.tr("Keine \u00dcberweisung ausgew\u00e4hlt"));

      // Jede ID nur einmal (numerisch kanonisiert, "042" = "42") - sonst wuerde derselbe Auftrag doppelt gesendet
      final Set<Long> unique = new LinkedHashSet<>();
      for (String id:ids)
      {
        try
        {
          if (!unique.add(Long.valueOf(id.trim())))
            throw new ApplicationException(i18n.tr("\u00dcberweisung mehrfach ausgew\u00e4hlt"));
        }
        catch (NumberFormatException nfe)
        {
          throw new ApplicationException(i18n.tr("Ung\u00fcltige Auftrags-ID"));
        }
      }

      final BeanService service = Application.getBootLoader().getBootable(BeanService.class);
      final SynchronizeEngine engine = service.get(SynchronizeEngine.class);

      // Pruefen und Starten atomar, damit zwei parallele Requests nicht beide durchkommen
      synchronized (Freigabe.class)
      {
        if (this.isRunning())
          throw new ApplicationException(i18n.tr("Synchronisierung l\u00e4uft bereits"));

        final Map<SynchronizeBackend,List<SynchronizeJob>> jobs = new LinkedHashMap<>();
        for (Long id:unique)
        {
          final AuslandsUeberweisung u = (AuslandsUeberweisung) Settings.getDBService().createObject(AuslandsUeberweisung.class,id.toString());
          if (u.ausgefuehrt() || !u.ueberfaellig())
            throw new ApplicationException(i18n.tr("\u00dcberweisung {0} ist bereits ausgef\u00fchrt oder noch nicht f\u00e4llig",id.toString()));

          final SynchronizeBackend backend = engine.getBackend(SynchronizeJobSepaUeberweisung.class,u.getKonto());
          final SynchronizeJob job = backend.create(SynchronizeJobSepaUeberweisung.class,u.getKonto());
          job.setContext(SynchronizeJob.CTX_ENTITY,u);
          jobs.computeIfAbsent(backend,b -> new ArrayList<>()).add(job);
        }

        for (Map.Entry<SynchronizeBackend,List<SynchronizeJob>> e:jobs.entrySet())
        {
          Logger.info("manual execution of " + e.getValue().size() + " transfers via " + e.getKey().getName());
          e.getKey().execute(e.getValue());
        }
      }
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(i18n.tr("Ausf\u00fchrung gestartet"),StatusBarMessage.TYPE_SUCCESS));
    }
    catch (ApplicationException ae)
    {
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(ae.getMessage(),StatusBarMessage.TYPE_ERROR));
    }
    this.response.sendRedirect("freigabe.html");
  }

  /**
   * Gibt die offene VoP-Entscheidung frei.
   * @throws Exception
   */
  public void approve() throws Exception
  {
    this.decide(true);
  }

  /**
   * Lehnt die offene VoP-Entscheidung ab.
   * @throws Exception
   */
  public void reject() throws Exception
  {
    this.decide(false);
  }

  /**
   * Uebernimmt die Entscheidung.
   * @param approve true bei Freigabe.
   * @throws Exception
   */
  private void decide(boolean approve) throws Exception
  {
    try
    {
      this.checkRequest();
      if (!Freigaben.decide(this.request.getParameter("vop"),approve))
        throw new ApplicationException(i18n.tr("Die Entscheidung ist nicht mehr offen"));
      Logger.info("VoP decision in web frontend: " + (approve ? "approved" : "rejected"));
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(approve ? i18n.tr("Auftrag freigegeben") : i18n.tr("Auftrag abgelehnt"),StatusBarMessage.TYPE_SUCCESS));
    }
    catch (ApplicationException ae)
    {
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(ae.getMessage(),StatusBarMessage.TYPE_ERROR));
    }
    this.response.sendRedirect("freigabe.html");
  }

  /**
   * Aktionen nur per POST und mit gueltigem Token.
   * @throws ApplicationException
   */
  private void checkRequest() throws ApplicationException
  {
    final HttpSession session = this.request.getSession(false);
    final String expected = session != null ? (String) session.getAttribute(TOKEN) : null;
    final String given = this.request.getParameter("token");
    if (!"POST".equals(this.request.getMethod()) || expected == null || given == null ||
        !MessageDigest.isEqual(expected.getBytes(),given.getBytes()))
      throw new ApplicationException(i18n.tr("Ung\u00fcltige Anfrage"));
  }
}
