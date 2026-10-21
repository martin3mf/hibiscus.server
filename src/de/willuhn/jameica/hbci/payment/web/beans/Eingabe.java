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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import de.willuhn.util.ApplicationException;

/**
 * Prueft Formulareingaben der Zahlungsfreigabe. Ohne Jameica-Abhaengigkeiten, damit testbar.
 */
public class Eingabe
{
  /**
   * Zulaessige Auftragstypen fuer die Batch-Aenderung.
   */
  public enum Typ { normal, termin, instant }

  /**
   * IDs werden in der Datenbank wiederverwendet (MAX(id)+1). Die Seite sendet deshalb "id:fingerabdruck",
   * damit ein geloeschter und neu angelegter Auftrag mit derselben ID nicht unbemerkt gesendet wird.
   * @param refs Referenzen aus dem Request.
   * @return ID -> Fingerabdruck, jede ID genau einmal.
   * @throws ApplicationException bei ungueltigen oder doppelten Referenzen.
   */
  public static Map<Long,String> refs(String[] refs) throws ApplicationException
  {
    final Map<Long,String> result = new LinkedHashMap<>();
    if (refs == null)
      return result;
    for (String r:refs)
    {
      if (r == null || !r.trim().matches("\\d{1,18}:[0-9a-f]{16}"))
        throw new ApplicationException("Ung\u00fcltige Auftragsreferenz");
      final String[] p = r.trim().split(":");
      if (result.put(Long.valueOf(p[0]),p[1]) != null)
        throw new ApplicationException("Auftrag mehrfach ausgew\u00e4hlt");
    }
    return result;
  }

  /**
   * @param parts die Werte, die einen Auftrag ausmachen.
   * @return die ersten 16 Hex-Zeichen von SHA-256 ueber die Werte.
   */
  public static String fp(Object... parts)
  {
    try
    {
      final StringBuilder sb = new StringBuilder();
      for (Object o:parts)
        sb.append(o).append('\u0001');
      final byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder();
      for (int i = 0;i < 8;i++)
        hex.append(String.format("%02x",h[i]));
      return hex.toString();
    }
    catch (java.security.NoSuchAlgorithmException e)
    {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @param s Betrag mit Punkt oder Komma als Dezimaltrenner, ohne Tausendertrenner.
   * @return der Betrag, groesser 0, maximal 2 Nachkommastellen.
   * @throws ApplicationException bei ungueltigem Betrag.
   */
  public static BigDecimal betrag(String s) throws ApplicationException
  {
    if (s == null || !s.trim().matches("\\d{1,9}([.,]\\d{1,2})?"))
      throw new ApplicationException("Ung\u00fcltiger Betrag");
    final BigDecimal b = new BigDecimal(s.trim().replace(',','.'));
    if (b.signum() <= 0)
      throw new ApplicationException("Der Betrag muss gr\u00f6\u00dfer als 0 sein");
    return b;
  }

  /**
   * @param s Datum im Format yyyy-MM-dd (input type=date).
   * @param today heute, ohne Uhrzeit.
   * @return das Datum, nicht vor heute.
   * @throws ApplicationException bei ungueltigem oder vergangenem Datum.
   */
  public static Date datum(String s, Date today) throws ApplicationException
  {
    final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setLenient(false);
    final Date d;
    try
    {
      if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}"))
        throw new ParseException(s,0);
      d = df.parse(s);
    }
    catch (ParseException e)
    {
      throw new ApplicationException("Ung\u00fcltiges Datum");
    }
    if (d.before(today))
      throw new ApplicationException("Das Datum liegt in der Vergangenheit");
    return d;
  }

  /**
   * Der Request wird als ISO-8859-1 gelesen; SEPA erlaubt ohnehin nur ASCII. So kann ein UTF-8-Umlaut nicht verfaelscht gespeichert werden.
   * @param s Verwendungszweck.
   * @return der Verwendungszweck.
   * @throws ApplicationException wenn leer, zu lang oder nicht ASCII.
   */
  public static String zweck(String s) throws ApplicationException
  {
    if (s == null || s.trim().isEmpty())
      throw new ApplicationException("Bitte einen Verwendungszweck eingeben");
    if (s.length() > 140)
      throw new ApplicationException("Der Verwendungszweck ist l\u00e4nger als 140 Zeichen");
    if (!s.matches("[\\x20-\\x7E]*"))
      throw new ApplicationException("Der Verwendungszweck enth\u00e4lt Zeichen au\u00dferhalb des SEPA-Zeichensatzes");
    return s;
  }

  /**
   * @param s Typ aus dem Request oder leer.
   * @return der Typ oder null, wenn er unveraendert bleibt.
   * @throws ApplicationException bei unbekanntem Typ.
   */
  public static Typ typ(String s) throws ApplicationException
  {
    if (s == null || s.isEmpty())
      return null;
    try
    {
      return Typ.valueOf(s);
    }
    catch (IllegalArgumentException e)
    {
      throw new ApplicationException("Ung\u00fcltiger Auftragstyp");
    }
  }

  /**
   * Eine Terminueberweisung braucht ein Datum nach heute, sonst lehnt die Bank sie ab.
   * @param termin true bei Terminueberweisung.
   * @param datum das Ausfuehrungsdatum.
   * @param today heute, ohne Uhrzeit.
   * @throws ApplicationException wenn das Datum nicht nach heute liegt.
   */
  public static void checkTermin(boolean termin, Date datum, Date today) throws ApplicationException
  {
    // Kalendertage vergleichen: ein Termin "heute 12:00" liegt nach heute 00:00, ist aber kein Folgetag
    if (termin && (datum == null || datum.before(tomorrow(today))))
      throw new ApplicationException("Eine Termin\u00fcberweisung braucht ein Datum nach heute");
  }

  /**
   * Hibiscus verlangt eine Lastschrift-Faelligkeit nach heute.
   * @param ziel die Faelligkeit.
   * @param termin der Ausfuehrungstermin.
   * @param today heute, ohne Uhrzeit.
   * @throws ApplicationException wenn die Faelligkeit nicht nach heute oder vor dem Termin liegt.
   */
  public static void checkZiel(Date ziel, Date termin, Date today) throws ApplicationException
  {
    if (ziel.before(tomorrow(today)))
      throw new ApplicationException("Die F\u00e4lligkeit muss nach heute liegen");
    if (ziel.before(termin))
      throw new ApplicationException("Die F\u00e4lligkeit liegt vor dem Ausf\u00fchrungstermin");
  }

  /**
   * @param today heute, ohne Uhrzeit.
   * @return morgen, 00:00 Uhr.
   */
  private static Date tomorrow(Date today)
  {
    final Calendar c = Calendar.getInstance();
    c.setTime(today);
    c.add(Calendar.DATE,1);
    return c.getTime();
  }

  /**
   * @return heute, 00:00 Uhr.
   */
  public static Date today()
  {
    final Calendar c = Calendar.getInstance();
    c.set(Calendar.HOUR_OF_DAY,0);
    c.set(Calendar.MINUTE,0);
    c.set(Calendar.SECOND,0);
    c.set(Calendar.MILLISECOND,0);
    return c.getTime();
  }
}
