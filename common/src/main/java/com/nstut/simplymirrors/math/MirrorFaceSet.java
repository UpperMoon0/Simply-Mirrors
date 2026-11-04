package com.nstut.simplymirrors.math;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An exact, order-independent multiset of mirror faces for mesh cache matching.
 *
 * Neither camera-distance sorting nor the periodic block-entity scan changes
 * the geometry. A mutable lookup key avoids per-face heap allocation on hits.
 * Counts (not just membership or a hash fingerprint) preserve correctness even
 * if an adapter accidentally supplies duplicate faces.
 */
public final class MirrorFaceSet {
    private final Map<FaceKey,Frequency> faces;
    private final FaceKey lookup=new FaceKey();
    private final int size;
    private int epoch;

    public MirrorFaceSet(List<MirrorSurface> surfaces) {
        size=surfaces.size();
        faces=new HashMap<FaceKey,Frequency>(Math.max(16,(int)(size/.75f)+1));
        for(MirrorSurface surface:surfaces) {
            FaceKey key=new FaceKey(surface);
            Frequency old=faces.get(key);
            if(old==null)faces.put(key,new Frequency());
            else old.count++;
        }
    }

    /** O(face count) exact match without allocations for unchanged input. */
    public boolean matches(List<MirrorSurface> surfaces) {
        if(size!=surfaces.size())return false;
        if(epoch==Integer.MAX_VALUE) {
            for(Frequency f:faces.values())f.visitedEpoch=0;
            epoch=0;
        }
        int current=++epoch;
        for(MirrorSurface surface:surfaces) {
            lookup.set(surface);
            Frequency f=faces.get(lookup);
            if(f==null)return false;
            if(f.visitedEpoch!=current) {
                f.visitedEpoch=current;
                f.visitedCount=0;
            }
            if(++f.visitedCount>f.count)return false;
        }
        // Equal total count + no missing entries + no count exceeded implies
        // each cached face's multiplicity is identical, without a second pass.
        return true;
    }

    private static final class Frequency {
        int count=1,visitedEpoch,visitedCount;
    }
    private static final class FaceKey {
        int x,y,z,nx,ny,nz;
        FaceKey() { }
        FaceKey(MirrorSurface surface) { set(surface); }
        void set(MirrorSurface surface) {
            x=surface.x;y=surface.y;z=surface.z;
            nx=surface.nx;ny=surface.ny;nz=surface.nz;
        }
        @Override public int hashCode() {
            int h=x;h=31*h+y;h=31*h+z;
            h=31*h+nx;h=31*h+ny;return 31*h+nz;
        }
        @Override public boolean equals(Object value) {
            if(!(value instanceof FaceKey))return false;
            FaceKey other=(FaceKey)value;
            return x==other.x&&y==other.y&&z==other.z
                    &&nx==other.nx&&ny==other.ny&&nz==other.nz;
        }
    }
}
