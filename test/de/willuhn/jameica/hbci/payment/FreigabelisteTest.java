package de.willuhn.jameica.hbci.payment;

import java.util.Date;
import java.util.List;

import org.kapott.hbci.GV_Result.GVRVoP.VoPResult;
import org.kapott.hbci.GV_Result.GVRVoP.VoPResultItem;
import org.kapott.hbci.GV_Result.GVRVoP.VoPStatus;

/**
 * Self-check for Freigabeliste. Run: java -cp ... de.willuhn.jameica.hbci.payment.FreigabelisteTest
 */
public class FreigabelisteTest
{
  private final static String IBAN = "DE89370400440532013000";
  private final static String IBAN2 = "GB82WEST12345698765432";

  public static void main(String[] args) throws Exception
  {
    final Date today = new Date();
    final Freigabeliste.Eintrag manual = Freigabeliste.manuell("de89 3704 0044 0532 0130 00"," 3MF  Media GmbH ",today);
    check(manual.getIban().equals(IBAN) && manual.getName().equals("3MF Media GmbH") && manual.isManuell(), "manual normalized");
    check(fails(() -> Freigabeliste.manuell("DE89370400440532013001","x",today)), "bad checksum refused");
    check(fails(() -> Freigabeliste.manuell("DE89","x",today)), "short iban refused");
    check(fails(() -> Freigabeliste.manuell(IBAN,"  ",today)), "empty name refused");
    check(Freigabeliste.manuell(IBAN,"M\u00fcller\tGmbH",today).getName().equals("M\u00fcller GmbH"), "tab removed, umlaut kept");

    final List<Freigabeliste.Eintrag> manualList = List.of(manual);
    // manual entry: RVMC and RVNM for IBAN + order name, any bank name
    check(Freigabeliste.isAllowed(result(item(VoPStatus.CLOSE_MATCH,IBAN,"3mf media gmbh","3MF GmbH")),manualList,false), "manual: close match");
    check(Freigabeliste.isAllowed(result(item(VoPStatus.NO_MATCH,IBAN,"3MF Media GmbH",null)),manualList,false), "manual: no match");
    check(!Freigabeliste.isAllowed(result(item(VoPStatus.NO_MATCH,IBAN2,"3MF Media GmbH",null)),manualList,false), "manual: other iban refused");
    check(!Freigabeliste.isAllowed(result(item(VoPStatus.NO_MATCH,IBAN,"Other GmbH",null)),manualList,false), "manual: other name refused");
    check(!Freigabeliste.isAllowed(result(item(VoPStatus.PENDING,IBAN,"3MF Media GmbH",null)),manualList,false), "pending never");
    check(!Freigabeliste.isAllowed(result(item(null,IBAN,"3MF Media GmbH",null)),manualList,false), "null status never");

    // learned entry: only RVMC with the same bank name
    final List<Freigabeliste.Eintrag> learned = Freigabeliste.learn(List.of(item(VoPStatus.CLOSE_MATCH,IBAN2,"Hurtta Oy","Hurtta OY Ltd"),item(VoPStatus.MATCH,IBAN,"x","x")),false,today);
    check(learned.size() == 1 && !learned.get(0).isManuell(), "learn only close match");
    check(Freigabeliste.isAllowed(result(item(VoPStatus.CLOSE_MATCH,IBAN2,"Hurtta Oy","Hurtta OY Ltd")),learned,false), "learned: same combination");
    check(!Freigabeliste.isAllowed(result(item(VoPStatus.CLOSE_MATCH,IBAN2,"Hurtta Oy","Someone Else")),learned,false), "learned: other bank name refused");
    check(!Freigabeliste.isAllowed(result(item(VoPStatus.NO_MATCH,IBAN2,"Hurtta Oy","Hurtta OY Ltd")),learned,false), "learned: no match refused");
    check(fails(() -> Freigabeliste.learn(List.of(item(VoPStatus.NO_MATCH,IBAN2,"Hurtta Oy",null)),false,today)), "cannot learn no match");
    check(fails(() -> Freigabeliste.learn(List.of(item(VoPStatus.CLOSE_MATCH,IBAN2,"Hurtta Oy","")),false,today)), "cannot learn without bank name");
    check(fails(() -> Freigabeliste.learn(List.of(item(VoPStatus.NOT_APPLICABLE,IBAN2,"x",null)),false,today)), "cannot learn RVNA when not allowed");
    check(Freigabeliste.learn(List.of(item(VoPStatus.NOT_APPLICABLE,IBAN2,"x",null)),true,today).isEmpty(), "RVNA skipped when allowed");

    // every item must be allowed; RVNA only with the setting
    final VoPResultItem rvna = item(VoPStatus.NOT_APPLICABLE,IBAN2,"x",null);
    final VoPResultItem ok = item(VoPStatus.CLOSE_MATCH,IBAN,"3MF Media GmbH","3MF GmbH");
    check(Freigabeliste.isAllowed(result(ok,rvna),manualList,true), "mixed allowed + RVNA with setting");
    check(!Freigabeliste.isAllowed(result(ok,rvna),manualList,false), "mixed allowed + RVNA without setting");
    check(!Freigabeliste.isAllowed(result(ok,item(VoPStatus.NO_MATCH,IBAN2,"y",null)),manualList,true), "one item not allowed");
    check(!Freigabeliste.isAllowed(result(),manualList,true), "empty result");
    check(!Freigabeliste.isAllowed((VoPResult) null,manualList,true), "null result");

    // storage roundtrip and dedupe
    final List<Freigabeliste.Eintrag> both = Freigabeliste.add(manualList,learned);
    check(Freigabeliste.add(both,List.of(manual)).size() == 2, "dedupe");
    final List<Freigabeliste.Eintrag> back = Freigabeliste.parse(Freigabeliste.format(both));
    check(back.size() == 2 && back.get(0).isManuell() && "Hurtta OY Ltd".equals(back.get(1).getBank()), "roundtrip");
    check(Freigabeliste.parse(new String[]{"broken","",null}).isEmpty(), "broken entries skipped");

    // Jameica stores at most 255 list entries
    final java.util.List<Freigabeliste.Eintrag> many = new java.util.ArrayList<>();
    for (int i = 0;i < Freigabeliste.MAX;i++)
      many.add(Freigabeliste.manuell(IBAN,"Name " + i,today));
    Freigabeliste.checkSize(many);
    many.add(manual);
    check(fails(() -> Freigabeliste.checkSize(many)), "list over 255 refused");

    System.out.println("FreigabelisteTest: OK");
  }

  interface Call { void run() throws Exception; }

  private static boolean fails(Call c)
  {
    try { c.run(); return false; } catch (de.willuhn.util.ApplicationException e) { return true; } catch (Exception e) { return false; }
  }

  private static VoPResultItem item(VoPStatus status, String iban, String original, String name)
  {
    final VoPResultItem i = new VoPResultItem();
    i.setStatus(status);
    i.setIban(iban);
    i.setOriginal(original);
    i.setName(name);
    return i;
  }

  private static VoPResult result(VoPResultItem... items)
  {
    final VoPResult r = new VoPResult();
    for (VoPResultItem i:items)
      r.getItems().add(i);
    return r;
  }

  private static void check(boolean b, String what)
  {
    if (!b)
      throw new AssertionError(what);
  }
}
