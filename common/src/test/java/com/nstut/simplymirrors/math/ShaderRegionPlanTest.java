package com.nstut.simplymirrors.math;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.nstut.simplymirrors.math.ShaderRegionPlan.*;

public class ShaderRegionPlanTest {
    @Test public void defaultProfileKeepsHistoryMipmapsAndUnprovenFinalInputsFull() {
        ShaderRegionPlan plan = complementaryDefault();
        assertEquals(0, plan.padding("final"));
        assertEquals(FULL, plan.padding("COMPOSITE7"));
        assertEquals(FULL, plan.padding("composite6"));
        assertEquals(FULL, plan.padding("composite5"));
        for (String name : new String[]{"composite","composite1","composite2",
                "composite3","composite4","unknown",null})
            assertEquals(FULL, plan.padding(name));
    }
    @Test public void fullConsumerPropagatesThroughLocalEdges() {
        ShaderRegionPlan plan = build(Arrays.asList(new Pass("a",false),
            new Pass("b",false,new Input("a",4)),
            new Pass("c",true,new Input("b",0)),
            new Pass("final",false,new Input("b",2))),"final");
        assertEquals(FULL,plan.padding("a"));
        assertEquals(FULL,plan.padding("b"));
        assertEquals(FULL,plan.padding("c"));
        assertEquals(0,plan.padding("final"));
    }
    @Test public void invalidOrUnobservedDependenciesFailClosed() {
        assertEquals(FULL, build(Arrays.asList(new Pass("a",false),
            new Pass("final",false,new Input("missing",0))),"final").padding("final"));
        assertEquals(FULL, build(Arrays.asList(new Pass("a",false),
            new Pass("a",false)),"a").padding("a"));
        assertEquals(FULL, build(Arrays.asList(new Pass("a",false),
            new Pass("final",false)),"final").padding("a"));
        assertEquals(FULL, build(Arrays.asList(new Pass("a",false,new Input("a",0))),"a").padding("a"));
    }
    @Test public void unboundedFootprintAndOverflowNeverCrop() {
        ShaderRegionPlan plan=build(Arrays.asList(new Pass("a",false),
            new Pass("b",false,new Input("a",Integer.MAX_VALUE)),
            new Pass("final",false,new Input("b",1))),"final");
        assertEquals(FULL,plan.padding("a"));
        assertEquals(1,plan.padding("b"));
    }
    @Test public void conflictingExperimentFlagsFailClosed() {
        requireExclusiveExperiment(false, true);
        requireExclusiveExperiment(true, false, false);
        try {
            requireExclusiveExperiment(true, false, true);
            fail("Conflicting experimental crop was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Dependency ROI"));
        }
    }
    @Test public void nativeViewportPlanContainsAllRequiredPixels() {
        ShaderRegionPlan plan=build(Arrays.asList(
            new Pass("composite5",false),
            new Pass("composite6",false,new Input("composite5",4)),
            new Pass("composite7",false,new Input("composite6",40)),
            new Pass("final",false,new Input("composite7",8))),"final");
        int last=Integer.MAX_VALUE;
        for(String name:new String[]{"composite5","composite6","composite7","final"}) {
            int p=plan.padding(name);
            assertTrue(p<=last);
            int[] r=ShaderViewportCrop.rectangle(700,260,420,400,1920,1080,0,0,1920,1080,p);
            assertNotNull(r);
            assertTrue(r[0]<=700 && r[1]<=260 && r[0]+r[2]>=1120 && r[1]+r[3]>=660);
            last=p;
        }
    }
}
