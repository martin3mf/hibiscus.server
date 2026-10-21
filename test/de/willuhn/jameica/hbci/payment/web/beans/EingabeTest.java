package de.willuhn.jameica.hbci.payment.web.beans;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Self-check for Eingabe. Run: java -cp ... de.willuhn.jameica.hbci.payment.web.beans.EingabeTest
 */
public class EingabeTest
{
  public static void main(String[] args) throws Exception
  {
    final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    final Date today = df.parse("2026-10-21");

    final String fp = Eingabe.fp("ueb","42","Name",1.5);
    check(fp.matches("[0-9a-f]{16}"), "fp format");
    check(fp.equals(Eingabe.fp("ueb","42","Name",1.5)), "fp stable");
    check(!fp.equals(Eingabe.fp("ueb","42","Name",1.6)), "fp changes with amount");
    check(!Eingabe.fp("a","bc").equals(Eingabe.fp("ab","c")), "fp separates parts");
    check(Eingabe.refs(new String[]{"042:" + fp,"7:" + fp}).keySet().toString().equals("[42, 7]"), "refs canonical");
    check(Eingabe.refs(new String[]{"42:" + fp}).get(42L).equals(fp), "refs keep fingerprint");
    check(fails(() -> Eingabe.refs(new String[]{"42:" + fp,"042:" + fp})), "duplicate id refused");
    check(fails(() -> Eingabe.refs(new String[]{"42"})), "id without fingerprint refused");
    check(fails(() -> Eingabe.refs(new String[]{"4x:" + fp})), "non-numeric id refused");
    check(fails(() -> Eingabe.refs(new String[]{"42:" + fp.toUpperCase()})), "bad fingerprint refused");
    check(fails(() -> Eingabe.refs(new String[]{null})), "null ref refused");
    check(Eingabe.refs(null).isEmpty(), "no refs");

    check(Eingabe.betrag("1234,5").compareTo(new BigDecimal("1234.50")) == 0, "comma decimal");
    check(Eingabe.betrag("0.01").compareTo(new BigDecimal("0.01")) == 0, "dot decimal");
    for (String bad:new String[]{"0","0,00","-5","1.234,56","1,234","abc","",null,"1e3","12.345"})
      check(fails(() -> Eingabe.betrag(bad)), "betrag refused: " + bad);

    check(Eingabe.datum("2026-10-21",today).equals(today), "today allowed");
    for (String bad:new String[]{"2026-10-20","2026-02-30","21.10.2026","",null,"2026-1-5"})
      check(fails(() -> Eingabe.datum(bad,today)), "datum refused: " + bad);

    check(Eingabe.zweck("RE 123/45 - Gutschrift").equals("RE 123/45 - Gutschrift"), "zweck ok");
    check(fails(() -> Eingabe.zweck("  ")), "empty zweck refused");
    check(fails(() -> Eingabe.zweck("x".repeat(141))), "long zweck refused");
    check(fails(() -> Eingabe.zweck("M\u00c3\u00bcller")), "utf8 bytes read as latin1 refused");
    check(fails(() -> Eingabe.zweck("a\nb")), "newline refused");

    check(Eingabe.typ("") == null && Eingabe.typ(null) == null, "empty typ = unchanged");
    check(Eingabe.typ("instant") == Eingabe.Typ.instant, "typ instant");
    check(fails(() -> Eingabe.typ("umbuchung")), "umbuchung not settable");

    Eingabe.checkTermin(false,today,today);
    Eingabe.checkTermin(true,df.parse("2026-10-22"),today);
    check(fails(() -> Eingabe.checkTermin(true,today,today)), "termin today refused");
    check(fails(() -> Eingabe.checkTermin(true,null,today)), "termin null refused");
    final Date noon = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-10-21 12:00");
    check(fails(() -> Eingabe.checkTermin(true,noon,today)), "termin today with time refused");
    Eingabe.checkTermin(false,noon,today);

    Eingabe.checkZiel(df.parse("2026-10-22"),today,today);
    check(fails(() -> Eingabe.checkZiel(today,today,today)), "ziel today refused");
    check(fails(() -> Eingabe.checkZiel(df.parse("2026-10-22"),df.parse("2026-10-23"),today)), "ziel before termin refused");

    System.out.println("EingabeTest: OK");
  }

  interface Call { void run() throws Exception; }

  private static boolean fails(Call c)
  {
    try { c.run(); return false; } catch (de.willuhn.util.ApplicationException e) { return true; } catch (Exception e) { return false; }
  }

  private static void check(boolean b, String what)
  {
    if (!b)
      throw new AssertionError(what);
  }
}
