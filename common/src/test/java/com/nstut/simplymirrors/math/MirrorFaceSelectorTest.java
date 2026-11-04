package com.nstut.simplymirrors.math;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** More than 16k candidate faces must not allow hidden faces to consume the budget. */
public class MirrorFaceSelectorTest {
    private static final float[] PROJECTION={1,0,0,0,0,1,0,0,0,0,.1f,0,0,0,0,1};
    @Test public void hiddenNearbyFacesCannotStarveVisibleDistantWall() {
        List<MirrorSurface> candidates=new ArrayList<MirrorSurface>();
        for(int j=0;j<22000;j++) {
            // Many nearer backward-facing and off-screen surfaces.
            int x=j%37-18,y=(j/37)%37-18,z=(j/1369)%17;
            candidates.add(new MirrorSurface(x,y,z,0,0,1));
        }
        List<MirrorSurface> wall=new ArrayList<MirrorSurface>();
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++) {
            MirrorSurface s=new MirrorSurface(x,y,20,0,0,-1);
            candidates.add(s);wall.add(s);
        }
        final double[] eye={.5,.5,-3};
        candidates.sort(Comparator.comparingDouble(s->Math.pow(s.cx-eye[0],2)+Math.pow(s.cy-eye[1],2)+Math.pow(s.cz-eye[2],2)));
        List<MirrorSurface> result=MirrorFaceSelector.select(candidates,PROJECTION,eye[0],eye[1],eye[2],
                MirrorFaceSelector.MAX_VISIBLE_FACES,s->true);
        assertEquals("All nine on-screen wall tiles survived >16k hidden candidates",wall.size(),result.size());
        assertTrue(result.containsAll(wall));
    }
    @Test public void legacy1536PrefixMustNotStarveVisible26_1_2Faces() {
        // The 26.1.2 renderer previously truncated the raw candidate list to
        // 1,536 before invoking MirrorFaceSelector. Exercise that exact count.
        List<MirrorSurface> candidates=new ArrayList<MirrorSurface>();
        for(int i=0;i<1537;i++) {
            // These blocks are closer than the forward-facing target but point away.
            candidates.add(new MirrorSurface(i%17-8,i/17%17-8,0,0,0,1));
        }
        MirrorSurface target=new MirrorSurface(0,0,12,0,0,-1);
        candidates.add(target);
        final double ex=.5,ey=.5,ez=-3;
        candidates.sort(Comparator.comparingDouble(s -> {
            double dx=s.cx-ex,dy=s.cy-ey,dz=s.cz-ez;
            return dx*dx+dy*dy+dz*dz;
        }));
        assertFalse(candidates.subList(0,1536).contains(target));
        List<MirrorSurface> visible=MirrorFaceSelector.select(candidates,PROJECTION,ex,ey,ez,16384,s->true);
        assertEquals(Collections.singletonList(target),visible);
    }

    @Test public void budgetCountsOnlyExposedFrontFacingProjectedSurfaces() {
        List<MirrorSurface> cells=new ArrayList<MirrorSurface>();
        for(int i=0;i<40;i++)cells.add(new MirrorSurface(0,0,5+i%5,0,0,-1));
        List<MirrorSurface> result=MirrorFaceSelector.select(cells,PROJECTION,.5,.5,-2,3,s->s.z%2==0);
        assertEquals(3,result.size());
        for(MirrorSurface s:result)assertEquals(0,s.z%2);
        assertTrue(MirrorFaceSelector.select(cells,PROJECTION,.5,.5,-2,0,s->true).isEmpty());
    }
}
