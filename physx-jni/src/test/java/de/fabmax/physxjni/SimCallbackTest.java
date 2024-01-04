package de.fabmax.physxjni;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import physx.PxTopLevelFunctions;
import physx.common.PxVec3;
import physx.geometry.PxBoxGeometry;
import physx.physics.*;
import physx.support.PxArray_PxContactPairPoint;

import java.util.*;

public class SimCallbackTest {
    @Test
    public void simCallbackTest() {
        try (MemoryStack mem = MemoryStack.stackPush()) {
            PxPhysics physics = PhysXTestEnv.physics;

            TestSimulationCallback simCallback = new TestSimulationCallback();

            // create scene with custom simulation callback
            PxSceneDesc sceneDesc = PxSceneDesc.createAt(mem, MemoryStack::nmalloc, physics.getTolerancesScale());
            sceneDesc.setSimulationEventCallback(simCallback);
            sceneDesc.setGravity(new PxVec3(0f, -9.81f, 0f));
            sceneDesc.setCpuDispatcher(PhysXTestEnv.defaultDispatcher);
            sceneDesc.setFilterShader(PxTopLevelFunctions.DefaultFilterShader());
            PxScene scene = physics.createScene(sceneDesc);

            // create a box with contact reporting enabled
            // the corresponding flags are encoded in word2 of simulation filter data
            int reportContactFlags = PxPairFlagEnum.eNOTIFY_TOUCH_FOUND.value | PxPairFlagEnum.eNOTIFY_TOUCH_LOST.value | PxPairFlagEnum.eNOTIFY_CONTACT_POINTS.value;
            PxFilterData boxFilterData = PxFilterData.createAt(mem, MemoryStack::nmalloc, 1, -1, reportContactFlags, 0);
            PxRigidDynamic box = PhysXTestEnv.createDefaultBox(0f, 5f, 0f, boxFilterData);
            scene.addActor(box);

            // create a trigger body
            // triggers don't collide with other bodies but trigger events are reported as soon as they overlap
            // with another body
            PxFilterData triggerFilterData = PxFilterData.createAt(mem, MemoryStack::nmalloc, 1, -1, 0, 0);
            PxBoxGeometry triggerGeom = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc, 1f, 0.5f, 1f);
            PxShapeFlags triggerFlags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) PxShapeFlagEnum.eTRIGGER_SHAPE.value);
            PxShape triggerShape = physics.createShape(triggerGeom, PhysXTestEnv.defaultMaterial, true, triggerFlags);
            triggerShape.setSimulationFilterData(triggerFilterData);
            PxRigidStatic trigger = PhysXTestEnv.createStaticBody(triggerShape, 0f, 3f, 0f);
            scene.addActor(trigger);

            // create default ground box, nothing special here
            PxBoxGeometry groundGeom = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc, 10f, 0.5f, 10f);
            PxRigidStatic ground = PhysXTestEnv.createStaticBody(groundGeom, 0f, 0f, 0f);
            scene.addActor(ground);

            // associate body pointers with human-readable names
            simCallback.actorNames.put(box, "Box");
            simCallback.actorNames.put(trigger, "Trigger");
            simCallback.actorNames.put(ground, "Ground");

            PhysXTestEnv.simulateScene(scene, 5f, box);

            Assertions.assertEquals(2, simCallback.contactBodies.size());
            Assertions.assertTrue(simCallback.contactBodies.contains(box));
            Assertions.assertTrue(simCallback.contactBodies.contains(ground));

            Assertions.assertEquals(2, simCallback.triggerBodies.size());
            Assertions.assertTrue(simCallback.triggerBodies.contains(box));
            Assertions.assertTrue(simCallback.triggerBodies.contains(trigger));

            // clean up
            scene.release();
            ground.release();
            trigger.release();
            box.release();
            simCallback.destroy();
        }
    }

    private static class TestSimulationCallback extends PxSimulationEventCallbackImpl {
        Map<PxActor, String> actorNames = new HashMap<>();

        Set<PxActor> contactBodies = new HashSet<>();
        Set<PxActor> triggerBodies = new HashSet<>();

        PxArray_PxContactPairPoint contacts = new PxArray_PxContactPairPoint(64);

        private String vec3ToString(PxVec3 vec3) {
            return String.format(Locale.ENGLISH, "(%.3f, %.3f, %.3f)", vec3.getX(), vec3.getY(), vec3.getZ());
        }

        @Override
        public void onContact(PxContactPairHeader pairHeader, PxContactPair pairs, int nbPairs) {
            PxActor actor0 = pairHeader.getActors(0);
            PxActor actor1 = pairHeader.getActors(1);
            contactBodies.add(actor0);
            contactBodies.add(actor1);
            Assertions.assertTrue(actorNames.containsKey(actor0));
            Assertions.assertTrue(actorNames.containsKey(actor1));
            String name0 = actorNames.get(actor0);
            String name1 = actorNames.get(actor1);

            for (int i = 0; i < nbPairs; i++) {
                PxContactPair pair = PxContactPair.arrayGet(pairs.getAddress(), i);
                PxPairFlags events = pair.getEvents();
                String event = "OTHER";
                if (events.isSet(PxPairFlagEnum.eNOTIFY_TOUCH_FOUND)) {
                    event = "TOUCH_FOUND";
                } else if (events.isSet(PxPairFlagEnum.eNOTIFY_TOUCH_LOST)) {
                    event = "TOUCH_LOST";
                }

                int contactPoints = pair.extractContacts(contacts.begin(), 64);
                System.out.println("onContact: " + name0 + " and " + name1 + ": " + event + ", " + contactPoints + " contact points");
                for (int j = 0; j < contactPoints; j++) {
                    PxContactPairPoint cp = contacts.get(j);
                    System.out.println("  pos: " + vec3ToString(cp.getPosition()) + ", nrm: " + vec3ToString(cp.getNormal())
                            + ", imp: " + vec3ToString(cp.getImpulse()) + ", sep: " + cp.getSeparation());
                }
            }
        }

        @Override
        public void onTrigger(PxTriggerPair pairs, int count) {
            for (int i = 0; i < count; i++) {
                PxTriggerPair pair = PxTriggerPair.arrayGet(pairs.getAddress(), i);
                PxActor actor0 = pair.getTriggerActor();
                PxActor actor1 = pair.getOtherActor();
                triggerBodies.add(actor0);
                triggerBodies.add(actor1);
                Assertions.assertTrue(actorNames.containsKey(actor0));
                Assertions.assertTrue(actorNames.containsKey(actor1));
                String name0 = actorNames.get(actor0);
                String name1 = actorNames.get(actor1);

                PxPairFlagEnum status = pair.getStatus();
                String event = "OTHER";
                if (status == PxPairFlagEnum.eNOTIFY_TOUCH_FOUND) {
                    event = "TRIGGER_ENTER";
                } else if (status == PxPairFlagEnum.eNOTIFY_TOUCH_LOST) {
                    event = "TRIGGER_EXIT";
                }
                System.out.println("onTrigger: " + name0 + " and " + name1 + ": " + event);
            }
        }
    }
}
