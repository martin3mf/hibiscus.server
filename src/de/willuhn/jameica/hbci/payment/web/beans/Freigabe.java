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

import org.json.JSONArray;
import org.json.JSONObject;
import org.kapott.hbci.GV_Result.GVRVoP.VoPResultItem;

import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.payment.Freigaben;
import de.willuhn.jameica.hbci.payment.Plugin;
import de.willuhn.jameica.hbci.rmi.AuslandsUeberweisung;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobSepaUeberweisung;
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
   * Liefert alle faelligen, noch nicht ausgefuehrten SEPA-Ueberweisungen.
   * @return Liste der faelligen Ueberweisungen.
   * @throws Exception
   */
  public List<AuslandsUeberweisung> getUeberweisungen() throws Exception
  {
    DBIterator list = Settings.getDBService().createList(AuslandsUeberweisung.class);
    list.addFilter("(ausgefuehrt is null or ausgefuehrt = 0)");
    list.setOrder("order by termin, id");
    final List<AuslandsUeberweisung> result = new ArrayList<>();
    while (list.hasNext())
    {
      final AuslandsUeberweisung u = (AuslandsUeberweisung) list.next();
      if (u.ueberfaellig())
        result.add(u);
    }
    return result;
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
   * AJAX: liefert Laufstatus, offene VoP-Entscheidung und faellige Ueberweisungen als JSON.
   * @throws Exception
   */
  public void status() throws Exception
  {
    final Format f = new Format();
    final JSONObject json = new JSONObject();
    json.put("running",this.isRunning());

    final Freigaben.Entscheidung e = this.getPending();
    if (e != null)
    {
      final JSONArray items = new JSONArray();
      for (VoPResultItem i:e.getItems())
      {
        items.put(new JSONObject()
          .put("status",i.getStatus() != null ? i.getStatus().getDescription() : "")
          .put("original",str(i.getOriginal()))
          .put("name",str(i.getName()))
          .put("iban",str(i.getIban()))
          .put("amount",i.getAmount() != null ? i.getAmount().toPlainString() : "")
          .put("usage",str(i.getUsage())));
      }
      json.put("pending",new JSONObject()
        .put("id",e.getId())
        .put("deadline",f.getLongDatum(e.getDeadline()))
        .put("auftrag",str(e.getAuftrag()))
        .put("text",str(e.getText()))
        .put("items",items));
    }

    final JSONArray list = new JSONArray();
    for (AuslandsUeberweisung u:this.getUeberweisungen())
    {
      list.put(new JSONObject()
        .put("id",u.getID())
        .put("termin",f.getDatum(u.getTermin()))
        .put("name",str(u.getGegenkontoName()))
        .put("iban",str(u.getGegenkontoNummer()))
        .put("betrag",f.getBetrag(u.getBetrag()))
        .put("zweck",str(u.getZweck())));
    }
    json.put("transfers",list);
    this.send(json);
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
      this.send(result(true,i18n.tr("Ausf\u00fchrung gestartet")));
    }
    catch (ApplicationException ae)
    {
      this.send(result(false,ae.getMessage()));
    }
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
      this.send(result(true,approve ? i18n.tr("Auftrag freigegeben") : i18n.tr("Auftrag abgelehnt")));
    }
    catch (ApplicationException ae)
    {
      this.send(result(false,ae.getMessage()));
    }
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

  /**
   * Schreibt die JSON-Antwort. Danach rendert der FrontController kein Template mehr (Response committed).
   * @param json die Antwort.
   * @throws Exception
   */
  private void send(JSONObject json) throws Exception
  {
    this.response.setContentType("application/json; charset=UTF-8");
    this.response.setHeader("Cache-Control","no-store");
    this.response.getWriter().write(json.toString());
    this.response.flushBuffer();
  }

  /**
   * @param ok true bei Erfolg.
   * @param text Meldung.
   * @return Ergebnis einer Aktion als JSON.
   */
  private static JSONObject result(boolean ok, String text)
  {
    return new JSONObject().put("ok",ok).put("text",text);
  }

  /**
   * @param s String oder null.
   * @return der String oder "".
   */
  private static String str(String s)
  {
    return s != null ? s : "";
  }
}
