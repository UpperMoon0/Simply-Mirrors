package com.nstut.simplymirrors.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Greedy, exact grid union of exposed coplanar faces.
 * No surface is discarded or approximated: each rectangle covers exactly its
 * contributing full-block faces, including holes and disconnected regions.
 */
public final class MirrorPatch implements MirrorGeometry {
    public final int x,y,z,nx,ny,nz,width,height;

    private MirrorPatch(int x,int y,int z,int nx,int ny,int nz,int width,int height) {
        this.x=x;this.y=y;this.z=z;this.nx=nx;this.ny=ny;this.nz=nz;
        this.width=width;this.height=height;
    }

    /** Original outward winding and 0.001-block surface offset are preserved. */
    @Override public double[][] corners() {
        if (nx!=0) {
            double plane=x+(nx>0?1:0)+nx*.001, y0=y,y1=y+height,z0=z,z1=z+width;
            return nx>0
                    ? new double[][]{{plane,y0,z1},{plane,y0,z0},{plane,y1,z0},{plane,y1,z1}}
                    : new double[][]{{plane,y0,z0},{plane,y0,z1},{plane,y1,z1},{plane,y1,z0}};
        }
        if (ny!=0) {
            double plane=y+(ny>0?1:0)+ny*.001,x0=x,x1=x+width,z0=z,z1=z+height;
            return ny>0
                    ? new double[][]{{x1,plane,z0},{x0,plane,z0},{x0,plane,z1},{x1,plane,z1}}
                    : new double[][]{{x1,plane,z1},{x0,plane,z1},{x0,plane,z0},{x1,plane,z0}};
        }
        double plane=z+(nz>0?1:0)+nz*.001,x0=x,x1=x+width,y0=y,y1=y+height;
        return nz>0
                ? new double[][]{{x0,y0,plane},{x1,y0,plane},{x1,y1,plane},{x0,y1,plane}}
                : new double[][]{{x1,y0,plane},{x0,y0,plane},{x0,y1,plane},{x1,y1,plane}};
    }

    /** Per-plane input only; individual mirror visibility remains the renderer's decision. */
    public static List<MirrorPatch> merge(List<MirrorSurface> surfaces) {
        List<MirrorPatch> patches=new ArrayList<MirrorPatch>();
        if (surfaces.isEmpty()) return patches;
        String plane=surfaces.get(0).planeKey();
        TreeMap<Integer,TreeSet<Integer>> rows=new TreeMap<Integer,TreeSet<Integer>>();
        for (MirrorSurface s:surfaces) {
            if (!plane.equals(s.planeKey())) throw new IllegalArgumentException("Cannot merge distinct mirror planes");
            int u=s.nx!=0?s.z:s.x, v=s.ny!=0?s.z:s.y;
            TreeSet<Integer> row=rows.get(v);
            if (row==null) {row=new TreeSet<Integer>();rows.put(v,row);}
            row.add(u);
        }
        MirrorSurface reference=surfaces.get(0);
        while (!rows.isEmpty()) {
            Map.Entry<Integer,TreeSet<Integer>> first=rows.firstEntry();
            int v=first.getKey(),u=first.getValue().first(),width=1,height=1;
            // Start at the lowest grid corner, so contiguous walls collapse
            // to one rectangle instead of one patch per camera-sorted strip.
            while(first.getValue().contains(u+width))width++;
            outer: while (true) {
                TreeSet<Integer> next=rows.get(v+height);
                if(next==null)break;
                for(int du=0;du<width;du++)if(!next.contains(u+du))break outer;
                height++;
            }
            for(int dv=0;dv<height;dv++) {
                TreeSet<Integer> row=rows.get(v+dv);
                for(int du=0;du<width;du++)row.remove(u+du);
                if(row.isEmpty())rows.remove(v+dv);
            }
            int x=reference.nx!=0?reference.x:u;
            int y=reference.ny!=0?reference.y:v;
            int z=reference.nx!=0?u:reference.ny!=0?v:reference.z;
            patches.add(new MirrorPatch(x,y,z,reference.nx,reference.ny,reference.nz,width,height));
        }
        return patches;
    }
}
