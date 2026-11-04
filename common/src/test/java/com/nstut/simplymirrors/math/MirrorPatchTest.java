package com.nstut.simplymirrors.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Covers exact world-space area and orientation, including negative coordinates and holes. */
public class MirrorPatchTest {
    private static MirrorSurface face(int u,int v,int nx,int ny,int nz) {
        int x=nx!=0?-9:u;
        int y=ny!=0?7:v;
        int z=nx!=0?u:ny!=0?v:13;
        return new MirrorSurface(x,y,z,nx,ny,nz);
    }

    @Test public void singletonCornersAreIdenticalForAllSixDirections() {
        for(int[] normal : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
            MirrorSurface s=face(-13,28,normal[0],normal[1],normal[2]);
            assertEquals(s.plane().isFrontFacing(s.cx+normal[0]*2.0,s.cy+normal[1]*2.0,s.cz+normal[2]*2.0,.025),
                s.frontFacing(s.cx+normal[0]*2.0,s.cy+normal[1]*2.0,s.cz+normal[2]*2.0,.025));
            assertEquals(s.plane().isFrontFacing(s.cx-normal[0]*2.0,s.cy-normal[1]*2.0,s.cz-normal[2]*2.0,.025),
                s.frontFacing(s.cx-normal[0]*2.0,s.cy-normal[1]*2.0,s.cz-normal[2]*2.0,.025));
            MirrorPatch patch=MirrorPatch.merge(Collections.singletonList(s)).get(0);
            double[][] expected=s.corners(),actual=patch.corners();
            for(int i=0;i<4;i++) assertArrayEquals(expected[i],actual[i],1e-12);
        }
    }

    @Test public void denseAndIrregularSurfacesKeepExactCoverage() {
        for(int[] normal : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
            for(int geometry=0;geometry<3;geometry++) {
                List<MirrorSurface> cells=new ArrayList<MirrorSurface>();
                for(int v=-7;v<21;v++)for(int u=-8;u<35;u++) {
                    if(geometry==1 && u>=5 && u<17 && v>=0 && v<12)continue; // interior hole
                    if(geometry==2 && (u+v)%3==0)continue; // disconnected irregular grid
                    cells.add(face(u,v,normal[0],normal[1],normal[2]));
                }
                Collections.shuffle(cells,new Random(711));
                List<MirrorPatch> merged=MirrorPatch.merge(cells);
                java.util.Set<String> occupancy=new java.util.HashSet<String>();
                for(MirrorSurface cell:cells) occupancy.add(cell.x+":"+cell.y+":"+cell.z);
                int area=0;
                for(MirrorPatch p:merged) {
                    assertEquals(normal[0],p.nx);assertEquals(normal[1],p.ny);assertEquals(normal[2],p.nz);
                    assertTrue(p.width>0 && p.height>0);
                    area+=p.width*p.height;
                    // No patch may cover a missing/occluded block, including holes.
                    for(int dv=0;dv<p.height;dv++)for(int du=0;du<p.width;du++) {
                        int u=p.nx!=0?p.z+du:p.x+du;
                        int v=p.ny!=0?p.z+dv:p.y+dv;
                        MirrorSurface covered=face(u,v,p.nx,p.ny,p.nz);
                        assertTrue("Merged patch must not fill a missing mirror",occupancy.contains(covered.x+":"+covered.y+":"+covered.z));
                    }
                }
                assertEquals("No face added or omitted",cells.size(),area);
                for(MirrorSurface cell:cells) {
                    int hits=0;
                    for(MirrorPatch p:merged) {
                        if(p.nx!=0 ? cell.z>=p.z&&cell.z<p.z+p.width&&cell.y>=p.y&&cell.y<p.y+p.height
                            : p.ny!=0 ? cell.x>=p.x&&cell.x<p.x+p.width&&cell.z>=p.z&&cell.z<p.z+p.height
                            : cell.x>=p.x&&cell.x<p.x+p.width&&cell.y>=p.y&&cell.y<p.y+p.height)hits++;
                    }
                    assertEquals("Exactly one patch covers each original cell",1,hits);
                }
                if(geometry==0)assertEquals("Dense grid should merge to one quad",1,merged.size());
                if(geometry==1)assertTrue("Hole must remain open",merged.size()>1);
            }
        }
    }

    @Test public void distinctPlanesCannotBeSilentlyCombined() {
        try {
            MirrorPatch.merge(Arrays.asList(new MirrorSurface(0,0,0,0,0,-1),new MirrorSurface(0,0,0,1,0,0)));
            fail("Mixed planes must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void emptyAndDuplicateCoordinatesAreHandled() {
        assertTrue(MirrorPatch.merge(Collections.<MirrorSurface>emptyList()).isEmpty());
        MirrorSurface s=face(3,2,0,0,-1);
        List<MirrorPatch> result=MirrorPatch.merge(Arrays.asList(s,s));
        assertEquals(1,result.size());
        assertEquals(1,result.get(0).width*result.get(0).height);
    }
}
