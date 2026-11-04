package com.nstut.simplymirrors.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MirrorFaceSetTest {
    private static List<MirrorSurface> wall(int width,int height) {
        List<MirrorSurface> faces=new ArrayList<MirrorSurface>();
        for(int x=0;x<width;x++)for(int y=0;y<height;y++)
            faces.add(new MirrorSurface(x,y,-1,0,0,-1));
        return faces;
    }
    private static List<MirrorSurface> copied(List<MirrorSurface> faces) {
        List<MirrorSurface> copy=new ArrayList<MirrorSurface>(faces.size());
        for(MirrorSurface s:faces)
            copy.add(new MirrorSurface(s.x,s.y,s.z,s.nx,s.ny,s.nz));
        return copy;
    }

    @Test public void cameraPanAndPeriodicRefreshDoNotInvalidateMesh() {
        List<MirrorSurface> original=wall(100,100);
        MirrorFaceSet cached=new MirrorFaceSet(original);
        for(int frame=0;frame<100;frame++) {
            double eyeX=frame*.71;
            List<MirrorSurface> scanned=copied(original);
            Collections.sort(scanned,Comparator.comparingDouble(s -> {
                double dx=s.cx-eyeX,dy=s.cy-50;
                return dx*dx+dy*dy;
            }));
            assertTrue("Static 10k faces invalidated by frame "+frame,cached.matches(scanned));
        }
    }

    @Test public void sameSizeEditsHolesAndNormalsInvalidateButRescanMatches() {
        List<MirrorSurface> original=wall(32,32);
        MirrorFaceSet cached=new MirrorFaceSet(original);
        List<MirrorSurface> refreshed=copied(original);
        Collections.reverse(refreshed);
        assertTrue(cached.matches(refreshed));

        refreshed.set(0,new MirrorSurface(-5,0,-1,0,0,-1));
        assertFalse("Same count changed geometry",cached.matches(refreshed));
        refreshed.set(0,new MirrorSurface(original.get(original.size()-1).x,
                original.get(original.size()-1).y,-1,0,0,1));
        assertFalse("Changed normal",cached.matches(refreshed));
        assertTrue("Rejected edits cannot poison cache matching",cached.matches(copied(original)));

        refreshed=copied(original);refreshed.remove(10);
        assertFalse("Hole changed coverage",cached.matches(refreshed));
        assertTrue(cached.matches(copied(original)));
    }

    @Test public void duplicateMultiplicitiesAndHashCollisionsCannotAlias() {
        List<MirrorSurface> originals=wall(4,4);
        originals.add(new MirrorSurface(0,0,-1,0,0,-1));
        MirrorFaceSet cached=new MirrorFaceSet(originals);
        List<MirrorSurface> copies=copied(originals);
        Collections.reverse(copies);
        assertTrue(cached.matches(copies));
        copies.set(0,new MirrorSurface(1,1,-1,0,0,-1));
        assertFalse("Different duplicate frequency with same total count",cached.matches(copies));
        assertTrue(cached.matches(copied(originals)));
        // These distinct position tuples collide under a basic 31-polynomial hash.
        List<MirrorSurface> one=Collections.singletonList(new MirrorSurface(0,31,-1,0,0,-1));
        List<MirrorSurface> another=Collections.singletonList(new MirrorSurface(1,0,-1,0,0,-1));
        assertFalse(new MirrorFaceSet(one).matches(another));
    }
}
