package tools.snapshot.nodes;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;

import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage.PromiseMessage;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.Resolution;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.actors.SPromise.STracingPromise;
import som.primitives.ObjectPrims.ClassPrim;
import som.primitives.ObjectPrimsFactory.ClassPrimFactory;
import tools.concurrency.TracingActors.TracingActor;
import tools.snapshot.SnapshotBackend;
import tools.snapshot.SnapshotBuffer;
import tools.snapshot.deserialization.DeserializationBuffer;
import tools.snapshot.deserialization.FixupInformation;


public abstract class PromiseSerializationNodes {
  private static final byte UNRESOLVED = 0;
  private static final byte CHAINED    = 1;
  private static final byte SUCCESS    = 2;
  private static final byte ERROR      = 3;

  static void handleReferencedPromise(final SPromise prom,
      final SnapshotBuffer sb, final int location) {
    if (sb.getOwner() != null && prom.getOwner() == sb.getOwner().getCurrentActor()) {
      long promLocation = SPromise.getPromiseClass().serialize(prom, sb);
      sb.putLongAt(location, promLocation);
    } else {
      // The Promise belong to another Actor
      TracingActor ta = (TracingActor) prom.getOwner();
      long promLocation = SPromise.getPromiseClass().getObjectLocation(prom);
      if (promLocation == -1) {
        ta.farReference(prom, sb, location);
      } else {
        sb.putLongAt(location, promLocation);
      }
    }
  }

  @GenerateNodeFactory
  public abstract static class PromiseSerializationNode extends AbstractSerializationNode {
    @Child ClassPrim classPrim = ClassPrimFactory.create(null);

    @Specialization(guards = "!prom.isCompleted()")
    public long doUnresolved(final SPromise prom, final SnapshotBuffer sb) {
      long location = getObjectLocation(prom, sb.getSnapshotVersion());
      if (location != -1) {
        return location;
      }

      int ncp;
      int nwr;
      int noe;
      PromiseMessage whenRes;
      PromiseMessage onError;
      ArrayList<PromiseMessage> whenResExt;
      ArrayList<PromiseMessage> onErrorExt;
      SPromise chainedProm;
      ArrayList<SPromise> chainedPromExt;
      synchronized (prom) {
        chainedProm = prom.getChainedPromiseUnsync();
        chainedPromExt = prom.getChainedPromiseExtUnsync();
        ncp = getObjectCnt(chainedProm, chainedPromExt);

        whenRes = prom.getWhenResolvedUnsync();
        whenResExt = prom.getWhenResolvedExtUnsync();
        nwr = getObjectCnt(whenRes, whenResExt);

        onError = prom.getOnError();
        onErrorExt = prom.getOnErrorExtUnsync();
        noe = getObjectCnt(onError, onErrorExt);
      }
      int start =
          sb.addObject(prom, SPromise.getPromiseClass(),
              1 + Integer.BYTES + 6 + Long.BYTES * (noe + nwr + ncp));
      int base = start;

      // resolutionstate
      if (prom.getResolutionStateUnsync() == Resolution.CHAINED) {
        sb.putByteAt(base, CHAINED);
      } else {
        sb.putByteAt(base, UNRESOLVED);
      }

      sb.putIntAt(base + 1, ((TracingActor) prom.getOwner()).getActorId());
      base += 1 + Integer.BYTES;
      base = serializeMessages(base, nwr, whenRes, whenResExt, sb);
      base = serializeMessages(base, noe, onError, onErrorExt, sb);
      serializeChainedPromises(base, ncp, chainedProm, chainedPromExt, sb);
      return sb.calculateReferenceB(start);
    }

    @Specialization(guards = "prom.isCompleted()")
    public long doResolved(final SPromise prom, final SnapshotBuffer sb) {
      long location = getObjectLocation(prom, sb.getSnapshotVersion());
      if (location != -1) {
        return location;
      }

      int ncp;
      int nwr;
      int noe;
      PromiseMessage whenRes;
      PromiseMessage onError;
      ArrayList<PromiseMessage> whenResExt;
      ArrayList<PromiseMessage> onErrorExt;
      SPromise chainedProm;
      ArrayList<SPromise> chainedPromExt;
      synchronized (prom) {
        chainedProm = prom.getChainedPromiseUnsync();
        chainedPromExt = prom.getChainedPromiseExtUnsync();
        ncp = getObjectCnt(chainedProm, chainedPromExt);

        whenRes = prom.getWhenResolvedUnsync();
        whenResExt = prom.getWhenResolvedExtUnsync();
        nwr = getObjectCnt(whenRes, whenResExt);

        onError = prom.getOnError();
        onErrorExt = prom.getOnErrorExtUnsync();
        noe = getObjectCnt(onError, onErrorExt);
      }
      int start = sb.addObject(prom, SPromise.getPromiseClass(),
          1 + 6 + Integer.BYTES + Integer.BYTES + Long.BYTES * (noe + nwr + ncp + 1));
      int base = start;

      // resolutionstate
      switch (prom.getResolutionStateUnsync()) {
        case SUCCESSFUL:
          sb.putByteAt(base, SUCCESS);
          break;
        case ERRONEOUS:
          sb.putByteAt(base, ERROR);
          break;
        default:
          throw new IllegalArgumentException("This shoud be unreachable");
      }

      Object value = prom.getValueForSnapshot();

      sb.putIntAt(base + 1, ((TracingActor) prom.getOwner()).getActorId());
      sb.putLongAt(base + 1 + Integer.BYTES,
          classPrim.executeEvaluated(value).serialize(value, sb));
      sb.putIntAt(base + 1 + +Integer.BYTES + Long.BYTES,
          ((STracingPromise) prom).getResolvingActor());
      base += (1 + Integer.BYTES + Integer.BYTES + Long.BYTES);
      base = serializeMessages(base, nwr, whenRes, whenResExt, sb);
      base = serializeMessages(base, noe, onError, onErrorExt, sb);
      serializeChainedPromises(base, ncp, chainedProm, chainedPromExt, sb);

      return sb.calculateReferenceB(start);
    }

    private int getObjectCnt(final Object obj, final ArrayList<? extends Object> extension) {
      if (obj == null) {
        return 0;
      } else if (extension == null) {
        return 1;
      } else {
        return extension.size() + 1;
      }
    }

    private int serializeMessages(final int start, final int cnt,
        final PromiseMessage whenRes, final ArrayList<PromiseMessage> whenResExt,
        final SnapshotBuffer sb) {

      int base = start;
      sb.putShortAt(base, (short) cnt);
      base += 2;
      if (cnt > 0) {
        if (whenRes.isDelivered()) {
          doDeliveredMessage(whenRes, base, sb);
        } else {
          sb.putLongAt(base, whenRes.forceSerialize(sb));
        }

        base += Long.BYTES;

        if (cnt > 1) {
          for (int i = 0; i < whenResExt.size(); i++) {
            PromiseMessage msg = whenResExt.get(i);
            if (msg.isDelivered()) {
              doDeliveredMessage(msg, base + i * Long.BYTES, sb);
            } else {
              sb.putLongAt(base + i * Long.BYTES, msg.forceSerialize(sb));
            }
          }
          base += whenResExt.size() * Long.BYTES;
        }
      }
      return base;
    }

    private void doDeliveredMessage(final PromiseMessage pm, final int location,
        final SnapshotBuffer sb) {
      assert pm.isDelivered();

      if (pm.getTarget() == sb.getOwner().getCurrentActor()) {
        sb.putLongAt(location, pm.forceSerialize(sb));
      } else {
        TracingActor ta = (TracingActor) pm.getTarget();
        ta.farReferenceMessage(pm, sb, location);
      }
    }

    @TruffleBoundary // TODO: can we do better than a boundary?
    private void serializeChainedPromises(final int start, final int cnt,
        final SPromise chainedProm,
        final ArrayList<SPromise> chainedPromExt, final SnapshotBuffer sb) {
      int base = start;
      sb.putShortAt(base, (short) cnt);
      base += 2;
      if (cnt > 0) {
        handleReferencedPromise(chainedProm, sb, base);
        base += Long.BYTES;

        if (cnt > 1) {
          for (int i = 0; i < chainedPromExt.size(); i++) {
            SPromise prom = chainedPromExt.get(i);
            handleReferencedPromise(prom, sb, base + i * Long.BYTES);
          }
        }
      }
    }

    @Override
    public SPromise deserialize(final DeserializationBuffer sb) {
      byte state = sb.get();
      assert state >= 0 && state <= 3;
      if (state == SUCCESS || state == ERROR) {
        return deserializeCompletedPromise(state, sb);
      } else {
        return deserializeUnresolvedPromise(sb);
      }
    }

    private SPromise deserializeCompletedPromise(final byte state,
        final DeserializationBuffer sb) {
      int ownerId = sb.getInt();
      Actor owner = SnapshotBackend.lookupActor(ownerId);
      Object value = sb.getReference();
      int resolver = sb.getInt();

      SPromise p = SPromise.createResolved(owner, value,
          state == SUCCESS ? Resolution.SUCCESSFUL : Resolution.ERRONEOUS);
      ((STracingPromise) p).setResolvingActorForSnapshot(resolver);

      if (DeserializationBuffer.needsFixup(value)) {
        sb.installFixup(new PromiseValueFixup(p));
      }

      int whenResolvedCnt = Short.toUnsignedInt(sb.getShort());
      for (int i = 0; i < whenResolvedCnt; i++) {
        PromiseMessage pm = (PromiseMessage) sb.getReference();
        p.registerWhenResolvedUnsynced(pm);
      }
      int onErrorCnt = Short.toUnsignedInt(sb.getShort());
      for (int i = 0; i < onErrorCnt; i++) {
        PromiseMessage pm = (PromiseMessage) sb.getReference();
        p.registerOnErrorUnsynced(pm);
      }

      int chainedPromCnt = Short.toUnsignedInt(sb.getShort());
      for (int i = 0; i < chainedPromCnt; i++) {
        Object remoteObj = sb.getReference();
        if (DeserializationBuffer.needsFixup(remoteObj)) {
          sb.installFixup(new ChainedPromiseFixup((STracingPromise) p));
        } else {
          initialiseChainedPromise((STracingPromise) p, (SPromise) remoteObj);
        }
      }
      return p;
    }

    private SPromise deserializeUnresolvedPromise(final DeserializationBuffer sb) {
      int ownerId = sb.getInt();
      Actor owner = SnapshotBackend.lookupActor(ownerId);
      SPromise promise = SPromise.createPromise(owner, false, false, null);

      // These messages aren't referenced by anything else, no need for fixup
      int whenResolvedCnt = sb.getShort();
      for (int i = 0; i < whenResolvedCnt; i++) {
        PromiseMessage pm = (PromiseMessage) sb.getReference();
        promise.registerWhenResolvedUnsynced(pm);
      }

      int onErrorCnt = sb.getShort();
      for (int i = 0; i < onErrorCnt; i++) {
        PromiseMessage pm = (PromiseMessage) sb.getReference();
        promise.registerOnErrorUnsynced(pm);
      }

      int chainedPromCnt = sb.getShort();
      for (int i = 0; i < chainedPromCnt; i++) {
        SPromise remote = (SPromise) sb.getReference();
        promise.addChainedPromise(remote);
      }

      return promise;
    }

    private static void initialiseChainedPromise(final STracingPromise p,
        final SPromise remote) {
      boolean complete = remote.isCompleted();
      int resolver = p.getResolvingActor();

      p.addChainedPromise(remote);
      remote.resolveFromSnapshot(p.getValueForSnapshot(), p.getResolutionStateUnsync(),
          SnapshotBackend.lookupActor(resolver), !complete);
      ((STracingPromise) remote).setResolvingActorForSnapshot(resolver);
    }

    public static class PromiseValueFixup extends FixupInformation {
      private final SPromise promise;

      public PromiseValueFixup(final SPromise promise) {
        this.promise = promise;
      }

      @Override
      public void fixUp(final Object o) {
        promise.setValueFromSnapshot(o);
      }
    }

    public static class ChainedPromiseFixup extends FixupInformation {
      private final STracingPromise parent;

      public ChainedPromiseFixup(final STracingPromise parent) {
        this.parent = parent;
      }

      @Override
      public void fixUp(final Object o) {
        initialiseChainedPromise(parent, (SPromise) o);
      }
    }
  }

  /**
   * Resolvers are values and can be passed directly without being wrapped.
   * There is only a single resolver for every promise. But promises may be chained.
   * Resolver contains a reference to the promise, which knows it's owner.
   * If Identity of Resolvers becomes an issue, just add the actor information and turn into a
   * singleton when deserializing
   */
  @GenerateNodeFactory
  public abstract static class ResolverSerializationNode extends AbstractSerializationNode {

    @Specialization
    public long doResolver(final SResolver resolver, final SnapshotBuffer sb,
        @Cached("getBuffer()") final SnapshotBuffer vb) {

      long location = getValueLocation(resolver);
      if (location != -1) {
        return location;
      }

      int base = vb.addValueObject(resolver, SResolver.getResolverClass(),
          Long.BYTES);
      SPromise prom = resolver.getPromise();
      handleReferencedPromise(prom, vb, base);
      return vb.calculateReferenceB(base);
    }

    @Override
    public SResolver deserialize(final DeserializationBuffer bb) {
      SPromise prom = (SPromise) bb.getReference();
      return SPromise.createResolver(prom);
    }
  }
}
