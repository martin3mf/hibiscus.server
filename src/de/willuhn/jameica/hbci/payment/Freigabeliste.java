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

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.kapott.hbci.GV_Result.GVRVoP.VoPResult;
import org.kapott.hbci.GV_Result.GVRVoP.VoPResultItem;
import org.kapott.hbci.GV_Result.GVRVoP.VoPStatus;

import de.willuhn.util.ApplicationException;

/**
 * Freigabeliste fuer den Namensabgleich (VoP).
 * Manuelle Eintraege (IBAN + Name) geben "Beinahe-Uebereinstimmung" und "Keine Uebereinstimmung" frei.
 * Gelernte Eintraege (IBAN + Name + Name laut Bank) geben nur "Beinahe-Uebereinstimmung" frei.
 * "Wartet auf Rueckmeldung" wird nie automatisch freigegeben.
 */
public class Freigabeliste
{
  private final static String SEP = "\t";

  /**
   * Jameica-Settings speichern String-Listen nur mit den Indizes 0-254.
   */
  final static int MAX = 255;

  /**
   * Ein Eintrag der Freigabeliste.
   */
  public static class Eintrag
  {
    private final String iban;
    private final String name;
    private final String bank; // null bei manuellen Eintraegen
    private final String datum;

    /**
     * ct.
     * @param iban IBAN, normalisiert.
     * @param name Name im Auftrag.
     * @param bank Name laut Bank oder null bei manuellen Eintraegen.
     * @param datum Datum der Anlage.
     */
    Eintrag(String iban, String name, String bank, String datum)
    {
      this.iban = iban;
      this.name = name;
      this.bank = bank;
      this.datum = datum;
    }

    /**
     * @return IBAN.
     */
    public String getIban()
    {
      return this.iban;
    }

    /**
     * @return Name im Auftrag.
     */
    public String getName()
    {
      return this.name;
    }

    /**
     * @return Name laut Bank oder null bei manuellen Eintraegen.
     */
    public String getBank()
    {
      return this.bank;
    }

    /**
     * @return true bei manuell angelegten Eintraegen.
     */
    public boolean isManuell()
    {
      return this.bank == null;
    }

    /**
     * @return Datum der Anlage.
     */
    public String getDatum()
    {
      return this.datum;
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
      return this.iban + SEP + this.name + SEP + (this.bank != null ? this.bank : "") + SEP + this.datum;
    }

    /**
     * @param s gespeicherter Eintrag.
     * @return der Eintrag oder null, wenn er nicht lesbar ist.
     */
    static Eintrag parse(String s)
    {
      final String[] p = s != null ? s.split(SEP,-1) : new String[0];
      if (p.length != 4 || p[0].isEmpty() || p[1].isEmpty())
        return null;
      return new Eintrag(p[0],p[1],p[2].isEmpty() ? null : p[2],p[3]);
    }
  }

  /**
   * @return die gespeicherte Freigabeliste.
   */
  public static synchronized List<Eintrag> load()
  {
    return parse(Settings.getVoPAllowList());
  }

  /**
   * Speichert die Freigabeliste. Lesen, Aendern und Speichern unter synchronized (Freigabeliste.class).
   * @param list die Freigabeliste.
   */
  public static synchronized void store(List<Eintrag> list) throws ApplicationException
  {
    checkSize(list);
    Settings.setVoPAllowList(format(list));
  }

  /**
   * @param list die Freigabeliste.
   * @throws ApplicationException wenn sie mehr Eintraege hat, als Jameica speichern kann.
   */
  public static void checkSize(List<Eintrag> list) throws ApplicationException
  {
    if (list.size() > MAX)
      throw new ApplicationException("Die Freigabeliste ist voll (" + MAX + " Eintr\u00e4ge). Bitte alte Eintr\u00e4ge entfernen.");
  }

  /**
   * Prueft, ob ein VoP-Ergebnis anhand der Freigabeliste automatisch freigegeben werden darf.
   * @param result das VoP-Ergebnis.
   * @param list die Freigabeliste.
   * @param notApplicable true, wenn "nicht anwendbar" (RVNA) freigegeben werden darf.
   * @return true nur, wenn jeder Posten freigegeben ist.
   */
  public static boolean isAllowed(VoPResult result, List<Eintrag> list, boolean notApplicable)
  {
    if (result == null || result.getItems() == null || result.getItems().isEmpty())
      return false;
    for (VoPResultItem i:result.getItems())
    {
      if (!isAllowed(i,list,notApplicable))
        return false;
    }
    return true;
  }

  /**
   * @param i ein Posten des VoP-Ergebnisses.
   * @param list die Freigabeliste.
   * @param notApplicable true, wenn "nicht anwendbar" (RVNA) freigegeben werden darf.
   * @return true, wenn der Posten freigegeben ist.
   */
  static boolean isAllowed(VoPResultItem i, List<Eintrag> list, boolean notApplicable)
  {
    final VoPStatus status = i.getStatus();
    if (status == VoPStatus.MATCH)
      return true;
    if (status == VoPStatus.NOT_APPLICABLE)
      return notApplicable;
    if (status != VoPStatus.CLOSE_MATCH && status != VoPStatus.NO_MATCH)
      return false;

    final String iban = iban(i.getIban());
    final String name = name(i.getOriginal());
    final String bank = name(i.getName());
    if (iban.isEmpty() || name.isEmpty())
      return false;
    for (Eintrag e:list)
    {
      if (!e.iban.equals(iban) || !name(e.name).equals(name))
        continue;
      if (e.isManuell())
        return true;
      if (status == VoPStatus.CLOSE_MATCH && !bank.isEmpty() && name(e.bank).equals(bank))
        return true;
    }
    return false;
  }

  /**
   * Liefert die gelernten Eintraege fuer ein Ergebnis, das der Nutzer freigibt und merken will.
   * @param items die Posten des VoP-Ergebnisses.
   * @param notApplicable true, wenn "nicht anwendbar" (RVNA) freigegeben werden darf.
   * @param today Datum der Anlage.
   * @return die neuen Eintraege.
   * @throws ApplicationException wenn ein Posten weder Uebereinstimmung, RVNA noch Beinahe-Uebereinstimmung mit Banknamen ist.
   */
  public static List<Eintrag> learn(List<VoPResultItem> items, boolean notApplicable, Date today) throws ApplicationException
  {
    if (items == null || items.isEmpty())
      throw new ApplicationException("Kein Ergebnis zum Merken");
    final String datum = new SimpleDateFormat("dd.MM.yyyy").format(today);
    final List<Eintrag> learned = new ArrayList<>();
    for (VoPResultItem i:items)
    {
      final VoPStatus status = i.getStatus();
      if (status == VoPStatus.MATCH || (status == VoPStatus.NOT_APPLICABLE && notApplicable))
        continue;
      final String iban = iban(i.getIban());
      final String name = clean(i.getOriginal());
      final String bank = clean(i.getName());
      if (status != VoPStatus.CLOSE_MATCH || iban.isEmpty() || name.isEmpty() || bank.isEmpty())
        throw new ApplicationException("Merken ist nur bei Beinahe-\u00dcbereinstimmung mit Namen laut Bank m\u00f6glich");
      learned.add(new Eintrag(iban,name,bank,datum));
    }
    return learned;
  }

  /**
   * Prueft und normalisiert einen manuellen Eintrag.
   * @param iban IBAN.
   * @param name Name des Empfaengers.
   * @param today Datum der Anlage.
   * @return der Eintrag.
   * @throws ApplicationException bei ungueltiger IBAN oder ungueltigem Namen.
   */
  public static Eintrag manuell(String iban, String name, Date today) throws ApplicationException
  {
    final String i = iban(iban);
    if (!i.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}") || !mod97(i))
      throw new ApplicationException("Ung\u00fcltige IBAN");
    final String n = clean(name);
    if (n.isEmpty() || n.length() > 140)
      throw new ApplicationException("Bitte einen Namen mit maximal 140 Zeichen eingeben");
    return new Eintrag(i,n,null,new SimpleDateFormat("dd.MM.yyyy").format(today));
  }

  /**
   * Fuegt Eintraege hinzu, die noch nicht in der Liste stehen.
   * @param list die bisherige Liste.
   * @param add die neuen Eintraege.
   * @return die neue Liste.
   */
  public static List<Eintrag> add(List<Eintrag> list, List<Eintrag> add)
  {
    final List<Eintrag> result = new ArrayList<>(list);
    for (Eintrag a:add)
    {
      final boolean exists = result.stream().anyMatch(e -> e.iban.equals(a.iban) && name(e.name).equals(name(a.name)) &&
                                                        name(e.bank).equals(name(a.bank)));
      if (!exists)
        result.add(a);
    }
    return result;
  }

  /**
   * @param list gespeicherte Eintraege.
   * @return lesbare Eintraege.
   */
  public static List<Eintrag> parse(String[] list)
  {
    final List<Eintrag> result = new ArrayList<>();
    for (String s:list != null ? list : new String[0])
    {
      final Eintrag e = Eintrag.parse(s);
      if (e != null)
        result.add(e);
    }
    return result;
  }

  /**
   * @param list Eintraege.
   * @return Eintraege zum Speichern.
   */
  public static String[] format(List<Eintrag> list)
  {
    return list.stream().map(Eintrag::toString).toArray(String[]::new);
  }

  /**
   * @param s IBAN.
   * @return IBAN ohne Leerzeichen, in Grossbuchstaben, oder "".
   */
  static String iban(String s)
  {
    return s != null ? s.replaceAll("\\s+","").toUpperCase(Locale.ROOT) : "";
  }

  /**
   * Steuer- und Trennzeichen entfernen, Leerraum zusammenfassen.
   * @param s Name.
   * @return bereinigter Name oder "".
   */
  static String clean(String s)
  {
    return s != null ? s.replaceAll("\\p{Cntrl}"," ").replaceAll("\\s+"," ").trim() : "";
  }

  /**
   * @param s Name.
   * @return Vergleichsform: bereinigt, ohne Gross-/Kleinschreibung.
   */
  static String name(String s)
  {
    return clean(s).toUpperCase(Locale.ROOT);
  }

  /**
   * @param iban normalisierte IBAN.
   * @return true, wenn die Pruefziffer stimmt.
   */
  static boolean mod97(String iban)
  {
    final String r = iban.substring(4) + iban.substring(0,4);
    final StringBuilder sb = new StringBuilder();
    for (char c:r.toCharArray())
      sb.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
    return new BigInteger(sb.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
  }
}
