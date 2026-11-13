package com.nstut.simplymirrors.math;

/** Conservative native-to-pass viewport transform; null means don't crop. */
public final class ShaderViewportCrop {
    private ShaderViewportCrop() { }

    public static int[] rectangle(int x,int y,int width,int height,
                                  int nativeWidth,int nativeHeight,
                                  int viewportX,int viewportY,int viewportWidth,int viewportHeight,
                                  int nativePad) {
        if(width<=0||height<=0||nativeWidth<=0||nativeHeight<=0||
           viewportWidth<=0||viewportHeight<=0||nativePad<0) return null;
        int x0=Math.max(0,x-nativePad);
        int y0=Math.max(0,y-nativePad);
        int x1=Math.min(nativeWidth,x+width+nativePad);
        int y1=Math.min(nativeHeight,y+height+nativePad);
        if(x1<=x0||y1<=y0) return null;
        int a=viewportX+(int)Math.floor((double)x0*viewportWidth/nativeWidth);
        int b=viewportY+(int)Math.floor((double)y0*viewportHeight/nativeHeight);
        int c=viewportX+(int)Math.ceil((double)x1*viewportWidth/nativeWidth);
        int d=viewportY+(int)Math.ceil((double)y1*viewportHeight/nativeHeight);
        int w=c-a, hh=d-b;
        if(w<=0||hh<=0||(double)w*hh>=.85*(double)viewportWidth*viewportHeight)
            return null;
        return new int[]{a,b,w,hh};
    }
}
