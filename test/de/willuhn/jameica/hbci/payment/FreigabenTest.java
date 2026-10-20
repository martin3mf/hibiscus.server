package de.willuhn.jameica.hbci.payment;

import java.util.concurrent.CompletableFuture;

import org.kapott.hbci.GV_Result.GVRVoP.VoPResult;
import org.kapott.hbci.GV_Result.GVRVoP.VoPResultItem;
import org.kapott.hbci.GV_Result.GVRVoP.VoPStatus;

/**
 * Self-check for Freigaben. Run: java -cp ... de.willuhn.jameica.hbci.payment.FreigabenTest
 */
public class FreigabenTest
{
  public static void main(String[] args) throws Exception
  {
    // RVNA detection: only when every item is NOT_APPLICABLE
    check(Freigaben.isNotApplicable(result(VoPStatus.NOT_APPLICABLE)), "single RVNA");
    check(Freigaben.isNotApplicable(result(VoPStatus.NOT_APPLICABLE,VoPStatus.NOT_APPLICABLE)), "all RVNA");
    check(!Freigaben.isNotApplicable(result(VoPStatus.NOT_APPLICABLE,VoPStatus.NO_MATCH)), "mixed RVNA/NO_MATCH");
    check(!Freigaben.isNotApplicable(result(VoPStatus.CLOSE_MATCH)), "close match");
    check(!Freigaben.isNotApplicable(result()), "empty result");
    check(!Freigaben.isNotApplicable(null), "null result");

    // Approve / reject from another thread
    check(decideLater(true), "approve");
    check(Freigaben.getPending() == null, "pending cleared after approve");
    check(!decideLater(false), "reject");

    // Timeout without decision -> false
    long t = System.currentTimeMillis();
    check(!Freigaben.await(result(VoPStatus.NO_MATCH),"txt","job",1), "timeout rejects");
    check(System.currentTimeMillis() - t >= 900, "timeout waited");
    check(Freigaben.getPending() == null, "pending cleared after timeout");

    // Wrong id, no pending decision and a second decision are refused
    check(!Freigaben.decide("nope",true), "no pending");
    CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> Freigaben.await(result(VoPStatus.NO_MATCH),"t","j",5));
    waitPending();
    check(!Freigaben.decide("wrong-id",true), "wrong id refused");
    String id = Freigaben.getPending().getId();
    check(Freigaben.decide(id,false), "first decision taken");
    check(!Freigaben.decide(id,true), "second decision refused");
    check(!f.get(), "first decision wins");

    // Late approval after the deadline is refused and the job stays rejected
    CompletableFuture<Boolean> late = CompletableFuture.supplyAsync(() -> Freigaben.await(result(VoPStatus.NO_MATCH),"t","j",1));
    waitPending();
    Freigaben.Entscheidung pending = Freigaben.getPending();
    Thread.sleep(1100);
    check(!Freigaben.decide(pending.getId(),true), "late approval refused");
    check(!late.get(), "late approval does not flip timeout");

    // Interrupt and approval at the same time: interrupt wins (monitor held so both land before the waiter wakes)
    final boolean[] got = new boolean[1];
    Thread waiter = new Thread(() -> got[0] = Freigaben.await(result(VoPStatus.NO_MATCH),"t","j",10));
    waiter.start();
    waitPending();
    Freigaben.Entscheidung race = Freigaben.getPending();
    synchronized (race)
    {
      waiter.interrupt();
      check(Freigaben.decide(race.getId(),true), "approval accepted before waiter wakes");
    }
    waiter.join(5000);
    check(!waiter.isAlive() && !got[0], "interrupt beats concurrent approval");

    System.out.println("FreigabenTest: OK");
  }

  private static boolean decideLater(boolean approve) throws Exception
  {
    CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> Freigaben.await(result(VoPStatus.NO_MATCH),"t","j",10));
    waitPending();
    check(Freigaben.decide(Freigaben.getPending().getId(),approve), "decide accepted");
    return f.get();
  }

  private static void waitPending() throws InterruptedException
  {
    for (int i=0;i<200 && Freigaben.getPending() == null;++i)
      Thread.sleep(10);
    check(Freigaben.getPending() != null, "pending registered");
  }

  private static VoPResult result(VoPStatus... states)
  {
    VoPResult r = new VoPResult();
    for (VoPStatus s:states)
    {
      VoPResultItem i = new VoPResultItem();
      i.setStatus(s);
      r.getItems().add(i);
    }
    return r;
  }

  private static void check(boolean b, String what)
  {
    if (!b)
      throw new AssertionError("FAILED: " + what);
  }
}
