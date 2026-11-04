package com.nstut.simplymirrors.math;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Apply the budget only to genuinely visible, exposed faces, not raw nearby candidates. */
public final class MirrorFaceSelector {
    public static final int MAX_VISIBLE_FACES=16384;
    private MirrorFaceSelector() { }

    /** The input is already distance-sorted, so nearer *visible* faces win. */
    public static List<MirrorSurface> select(List<MirrorSurface> candidates,
            float[] viewProjection, double eyeX,double eyeY,double eyeZ,
            int visibleLimit, Predicate<MirrorSurface> isExposed) {
        if(visibleLimit<0)throw new IllegalArgumentException("Negative visible-face limit");
        List<MirrorSurface> visible=new ArrayList<MirrorSurface>();
        for (MirrorSurface s:candidates) {
            if(visible.size()>=visibleLimit)break;
            double x=s.cx-eyeX,y=s.cy-eyeY,z=s.cz-eyeZ;
            if(x*x+y*y+z*z>32*32||!s.frontFacing(eyeX,eyeY,eyeZ,.025))continue;
            double w=viewProjection[3]*x+viewProjection[7]*y+viewProjection[11]*z+viewProjection[15];
            if(w<=.01)continue;
            double screenX=viewProjection[0]*x+viewProjection[4]*y+viewProjection[8]*z+viewProjection[12];
            double screenY=viewProjection[1]*x+viewProjection[5]*y+viewProjection[9]*z+viewProjection[13];
            if(Math.abs(screenX)>=w+1.5||Math.abs(screenY)>=w+1.5)continue;
            if(isExposed.test(s))visible.add(s);
        }
        return visible;
    }
}
