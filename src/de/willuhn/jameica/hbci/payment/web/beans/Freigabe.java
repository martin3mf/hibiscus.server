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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.rmi.RemoteException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import de.willuhn.jameica.hbci.rmi.BaseUeberweisung;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.SepaLastschrift;
import de.willuhn.jameica.hbci.rmi.SepaSammelLastBuchung;
import de.willuhn.jameica.hbci.rmi.SepaSammelLastschrift;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobSepaLastschrift;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobSepaSammelLastschrift;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobSepaUeberweisung;
import de.willuhn.jameica.services.BeanService;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.webadmin.annotation.Request;
import de.willuhn.jameica.webadmin.annotation.Response;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Controller fuer die manuelle Ausfuehrung und Bearbeitung von SEPA-Ueberweisungen und -Lastschriften und die VoP-Freigabe.
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
   * Liefert die noch nicht ausgefuehrten Auftraege eines Typs.
   * @param type AuslandsUeberweisung oder SepaLastschrift.
   * @param all true: alle offenen, false: nur faellige.
   * @return Liste der Auftraege.
   * @throws Exception
   */
  private <T extends BaseUeberweisung> List<T> list(Class<T> type, boolean all) throws Exception
  {
    DBIterator list = Settings.getDBService().createList(type);
    list.addFilter("(ausgefuehrt is null or ausgefuehrt = 0)");
    list.setOrder("order by termin, id");
    final List<T> result = new ArrayList<>();
    while (list.hasNext())
    {
      final T u = type.cast(list.next());
      if (all || u.ueberfaellig())
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

    final boolean all = "1".equals(this.request.getParameter("all"));
    final JSONArray transfers = new JSONArray();
    for (AuslandsUeberweisung u:this.list(AuslandsUeberweisung.class,all))
    {
      transfers.put(json(f,u)
        .put("typ",u.isUmbuchung() ? "umbuchung" : u.isInstantPayment() ? "instant" : u.isTerminUeberweisung() ? "termin" : "normal"));
    }
    json.put("transfers",transfers);

    final JSONArray debits = new JSONArray();
    for (SepaLastschrift l:this.list(SepaLastschrift.class,all))
    {
      debits.put(json(f,l)
        .put("ziel",f.getDatum(l.getTargetDate()))
        .put("zielIso",iso(l.getTargetDate()))
        .put("mandat",str(l.getMandateId()))
        .put("sequenz",l.getSequenceType() != null ? l.getSequenceType().getDescription() : ""));
    }
    json.put("debits",debits);

    final JSONArray collective = new JSONArray();
    DBIterator it = Settings.getDBService().createList(SepaSammelLastschrift.class);
    it.addFilter("(ausgefuehrt is null or ausgefuehrt = 0)");
    it.setOrder("order by termin, id");
    while (it.hasNext())
    {
      final SepaSammelLastschrift l = (SepaSammelLastschrift) it.next();
      if (!all && !l.ueberfaellig())
        continue;
      collective.put(new JSONObject()
        .put("id",l.getID())
        .put("termin",f.getDatum(l.getTermin()))
        .put("faellig",l.ueberfaellig())
        .put("ziel",f.getDatum(l.getTargetDate()))
        .put("bezeichnung",str(l.getBezeichnung()))
        .put("anzahl",l.getBuchungen().size())
        .put("summe",f.getBetrag(l.getSumme().doubleValue())));
    }
    json.put("collective",collective);
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

      // Jede ID nur einmal - sonst wuerde derselbe Auftrag doppelt gesendet
      final Set<Long> transferIds = Eingabe.ids(this.request.getParameterValues("id"));
      final Set<Long> debitIds = Eingabe.ids(this.request.getParameterValues("last"));
      final Set<Long> collectiveIds = Eingabe.ids(this.request.getParameterValues("sammel"));
      if (transferIds.isEmpty() && debitIds.isEmpty() && collectiveIds.isEmpty())
        throw new ApplicationException(i18n.tr("Kein Auftrag ausgew\u00e4hlt"));

      final BeanService service = Application.getBootLoader().getBootable(BeanService.class);
      final SynchronizeEngine engine = service.get(SynchronizeEngine.class);
      final Date today = Eingabe.today();

      // Pruefen und Starten atomar, damit zwei parallele Requests nicht beide durchkommen
      synchronized (Freigaben.LOCK)
      {
        if (this.isRunning())
          throw new ApplicationException(i18n.tr("Synchronisierung l\u00e4uft bereits"));

        final Map<SynchronizeBackend,List<SynchronizeJob>> jobs = new LinkedHashMap<>();
        for (Long id:transferIds)
        {
          final AuslandsUeberweisung u = load(AuslandsUeberweisung.class,id);
          // Terminueberweisungen duerfen vor dem Termin gesendet werden, die Bank fuehrt sie am Termin aus
          if (u.isTerminUeberweisung())
            Eingabe.checkTermin(true,u.getTermin(),today);
          else if (!u.ueberfaellig())
            throw new ApplicationException(i18n.tr("\u00dcberweisung {0} ist noch nicht f\u00e4llig",id.toString()));
          addJob(jobs,engine,SynchronizeJobSepaUeberweisung.class,u.getKonto(),u);
        }
        for (Long id:debitIds)
        {
          final SepaLastschrift l = load(SepaLastschrift.class,id);
          if (!l.ueberfaellig())
            throw new ApplicationException(i18n.tr("Lastschrift {0} ist noch nicht f\u00e4llig",id.toString()));
          addJob(jobs,engine,SynchronizeJobSepaLastschrift.class,l.getKonto(),l);
        }
        for (Long id:collectiveIds)
        {
          final SepaSammelLastschrift l = (SepaSammelLastschrift) Settings.getDBService().createObject(SepaSammelLastschrift.class,id.toString());
          if (l.isNewObject() || l.ausgefuehrt() || !l.ueberfaellig())
            throw new ApplicationException(i18n.tr("Sammellastschrift {0} fehlt, ist ausgef\u00fchrt oder noch nicht f\u00e4llig",id.toString()));
          addJob(jobs,engine,SynchronizeJobSepaSammelLastschrift.class,l.getKonto(),l);
        }

        for (Map.Entry<SynchronizeBackend,List<SynchronizeJob>> e:jobs.entrySet())
        {
          Logger.info("manual execution of " + e.getValue().size() + " orders via " + e.getKey().getName());
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
   * Setzt Typ und/oder Datum fuer mehrere Ueberweisungen in einer Transaktion.
   * @throws Exception
   */
  public void batch() throws Exception
  {
    try
    {
      this.checkRequest();
      final Set<Long> ids = Eingabe.ids(this.request.getParameterValues("id"));
      if (ids.isEmpty())
        throw new ApplicationException(i18n.tr("Keine \u00dcberweisung ausgew\u00e4hlt"));
      final Date today = Eingabe.today();
      final Eingabe.Typ typ = Eingabe.typ(this.request.getParameter("typ"));
      final String d = this.request.getParameter("datum");
      final Date datum = d == null || d.isEmpty() ? null : Eingabe.datum(d,today);
      if (typ == null && datum == null)
        throw new ApplicationException(i18n.tr("Bitte Typ oder Datum w\u00e4hlen"));

      synchronized (Freigaben.LOCK)
      {
        if (this.isRunning())
          throw new ApplicationException(i18n.tr("Synchronisierung l\u00e4uft bereits"));

        final List<AuslandsUeberweisung> list = new ArrayList<>();
        for (Long id:ids)
        {
          final AuslandsUeberweisung u = load(AuslandsUeberweisung.class,id);
          if (typ != null)
          {
            u.setTerminUeberweisung(typ == Eingabe.Typ.termin);
            u.setInstantPayment(typ == Eingabe.Typ.instant);
          }
          if (datum != null)
            u.setTermin(datum);
          Eingabe.checkTermin(u.isTerminUeberweisung(),u.getTermin(),today);
          list.add(u);
        }
        store(list);
      }
      Logger.info("web batch change of " + ids.size() + " transfers: typ=" + typ + ", datum=" + d);
      this.send(result(true,i18n.tr("{0} \u00dcberweisung(en) ge\u00e4ndert",String.valueOf(ids.size()))));
    }
    catch (ApplicationException ae)
    {
      this.send(result(false,ae.getMessage()));
    }
  }

  /**
   * Fasst Einzellastschriften zu Sammellastschriften zusammen, je Konto, Sequenztyp, Lastschriftart und Faelligkeit
   * (wie Hibiscus' SepaLastschriftMerger, der einen Desktop-Dialog braucht). Die Einzellastschriften werden geloescht,
   * sonst wuerde derselbe Betrag doppelt eingezogen. Alles in einer Transaktion.
   * @throws Exception
   */
  public void merge() throws Exception
  {
    try
    {
      this.checkRequest();
      final Set<Long> ids = Eingabe.ids(this.request.getParameterValues("last"));
      if (ids.isEmpty())
        throw new ApplicationException(i18n.tr("Keine Lastschrift ausgew\u00e4hlt"));

      int created = 0;
      synchronized (Freigaben.LOCK)
      {
        if (this.isRunning())
          throw new ApplicationException(i18n.tr("Synchronisierung l\u00e4uft bereits"));

        final SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy");
        final Map<String,List<SepaLastschrift>> groups = new LinkedHashMap<>();
        for (Long id:ids)
        {
          final SepaLastschrift l = load(SepaLastschrift.class,id);
          if (l.getSequenceType() == null || l.getType() == null || l.getTargetDate() == null)
            throw new ApplicationException(i18n.tr("Lastschrift {0}: Sequenztyp, Art oder F\u00e4lligkeit fehlt",id.toString()));
          final String key = l.getKonto().getID() + "/" + l.getSequenceType().name() + "/" + l.getType().name() + "/" + df.format(l.getTargetDate());
          groups.computeIfAbsent(key,k -> new ArrayList<>()).add(l);
        }

        // Transaktion an der ersten neuen Sammellastschrift wie im Hibiscus-Merger, nicht an einer Einzellastschrift, die geloescht wird
        SepaSammelLastschrift tx = null;
        try
        {
          for (List<SepaLastschrift> group:groups.values())
          {
            final SepaLastschrift g = group.get(0);
            Date termin = g.getTermin();
            for (SepaLastschrift l:group)
            {
              if (l.getTermin() != null && (termin == null || l.getTermin().before(termin)))
                termin = l.getTermin();
            }
            final SepaSammelLastschrift s = (SepaSammelLastschrift) Settings.getDBService().createObject(SepaSammelLastschrift.class,null);
            s.setKonto(g.getKonto());
            s.setBezeichnung(i18n.tr("{0} {1} vom {2}",g.getSequenceType().getDescription(),g.getType().getDescription(),df.format(new Date())));
            s.setSequenceType(g.getSequenceType());
            s.setType(g.getType());
            s.setTargetDate(g.getTargetDate());
            s.setTermin(termin);
            if (tx == null)
            {
              tx = s;
              tx.transactionBegin();
            }
            s.store();
            for (SepaLastschrift l:group)
            {
              final SepaSammelLastBuchung b = s.createBuchung();
              b.setBetrag(l.getBetrag());
              b.setCreditorId(l.getCreditorId());
              b.setEndtoEndId(l.getEndtoEndId());
              b.setGegenkontoBLZ(l.getGegenkontoBLZ());
              b.setGegenkontoName(l.getGegenkontoName());
              b.setGegenkontoNummer(l.getGegenkontoNummer());
              b.setMandateId(l.getMandateId());
              b.setSignatureDate(l.getSignatureDate());
              b.setZweck(l.getZweck());
              b.store();
              Logger.info("web merge: debit " + l.getID() + " (" + f(l.getBetrag()) + ") -> collective debit " + s.getID());
              l.delete();
            }
            created++;
          }
          tx.transactionCommit();
        }
        catch (Exception e)
        {
          if (tx != null)
            tx.transactionRollback();
          throw e;
        }
      }
      this.send(result(true,i18n.tr("{0} Lastschrift(en) zu {1} Sammellastschrift(en) zusammengefasst",String.valueOf(ids.size()),String.valueOf(created))));
    }
    catch (ApplicationException ae)
    {
      this.send(result(false,ae.getMessage()));
    }
  }

  /**
   * Aendert Betrag, Verwendungszweck und Termin eines Auftrags (bei Lastschriften auch die Faelligkeit).
   * Name und IBAN sind bewusst nicht aenderbar.
   * @throws Exception
   */
  public void edit() throws Exception
  {
    try
    {
      this.checkRequest();
      final boolean debit = "last".equals(this.request.getParameter("kind"));
      final Set<Long> ids = Eingabe.ids(new String[]{this.request.getParameter("id")});
      final Date today = Eingabe.today();
      final BigDecimal betrag = Eingabe.betrag(this.request.getParameter("betrag"));
      final String zweck = Eingabe.zweck(this.request.getParameter("zweck"));
      final Date termin = Eingabe.datum(this.request.getParameter("datum"),today);
      final Date ziel = debit ? Eingabe.datum(this.request.getParameter("ziel"),today) : null;
      if (ziel != null)
        Eingabe.checkZiel(ziel,termin,today);

      synchronized (Freigaben.LOCK)
      {
        if (this.isRunning())
          throw new ApplicationException(i18n.tr("Synchronisierung l\u00e4uft bereits"));

        final Long id = ids.iterator().next();
        final BaseUeberweisung u = debit ? load(SepaLastschrift.class,id) : load(AuslandsUeberweisung.class,id);
        final String before = f(u.getBetrag()) + " / " + u.getZweck() + " / " + iso(u.getTermin());
        u.setBetrag(betrag.doubleValue());
        u.setZweck(zweck);
        u.setTermin(termin);
        if (debit)
          ((SepaLastschrift) u).setTargetDate(ziel);
        else
          Eingabe.checkTermin(((AuslandsUeberweisung) u).isTerminUeberweisung(),termin,today);
        store(List.of(u));
        Logger.info("web edit of " + (debit ? "debit " : "transfer ") + id + ": " + before + " -> " + f(u.getBetrag()) + " / " + zweck + " / " + iso(termin));
      }
      this.send(result(true,i18n.tr("Auftrag gespeichert")));
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
   * Laedt einen offenen Auftrag.
   * @param type Typ des Auftrags.
   * @param id ID des Auftrags.
   * @return der Auftrag.
   * @throws Exception wenn er fehlt oder schon ausgefuehrt ist.
   */
  private static <T extends BaseUeberweisung> T load(Class<T> type, Long id) throws Exception
  {
    final T u = type.cast(Settings.getDBService().createObject(type,id.toString()));
    if (u.isNewObject())
      throw new ApplicationException(i18n.tr("Auftrag {0} nicht gefunden",id.toString()));
    if (u.ausgefuehrt())
      throw new ApplicationException(i18n.tr("Auftrag {0} ist bereits ausgef\u00fchrt",id.toString()));
    return u;
  }

  /**
   * Speichert alle Auftraege oder keinen. Hibiscus prueft dabei selbst Zeichensatz, Laenge und Umbuchung/Termin.
   * @param list die Auftraege.
   * @throws Exception
   */
  private static void store(List<? extends BaseUeberweisung> list) throws Exception
  {
    final BaseUeberweisung first = list.get(0);
    first.transactionBegin();
    try
    {
      for (BaseUeberweisung u:list)
        u.store();
      first.transactionCommit();
    }
    catch (Exception e)
    {
      first.transactionRollback();
      throw e;
    }
  }

  /**
   * Haengt einen Sendejob fuer den Auftrag an.
   * @param jobs Jobs je Backend.
   * @param engine die Synchronize-Engine.
   * @param type Jobtyp.
   * @param konto Konto des Auftrags.
   * @param u der Auftrag.
   * @throws Exception
   */
  private static void addJob(Map<SynchronizeBackend,List<SynchronizeJob>> jobs, SynchronizeEngine engine, Class<? extends SynchronizeJob> type, Konto konto, Object u) throws Exception
  {
    final SynchronizeBackend backend = engine.getBackend(type,konto);
    final SynchronizeJob job = backend.create(type,konto);
    job.setContext(SynchronizeJob.CTX_ENTITY,u);
    jobs.computeIfAbsent(backend,b -> new ArrayList<>()).add(job);
  }

  /**
   * @param f Formatierer.
   * @param u Auftrag.
   * @return die gemeinsamen Felder als JSON.
   * @throws RemoteException
   */
  private static JSONObject json(Format f, BaseUeberweisung u) throws RemoteException
  {
    return new JSONObject()
      .put("id",u.getID())
      .put("termin",f.getDatum(u.getTermin()))
      .put("terminIso",iso(u.getTermin()))
      .put("faellig",u.ueberfaellig())
      .put("name",str(u.getGegenkontoName()))
      .put("iban",str(u.getGegenkontoNummer()))
      .put("betrag",f.getBetrag(u.getBetrag()))
      .put("wert",f(u.getBetrag()))
      .put("zweck",str(u.getZweck()));
  }

  /**
   * @param d Datum oder null.
   * @return yyyy-MM-dd oder "".
   */
  private static String iso(Date d)
  {
    return d != null ? new SimpleDateFormat("yyyy-MM-dd").format(d) : "";
  }

  /**
   * @param d Betrag.
   * @return Betrag mit 2 Nachkommastellen, Punkt als Trenner.
   */
  private static String f(double d)
  {
    return BigDecimal.valueOf(d).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();
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
