package com.nstut.simplymirrors.math;

/** Small column-major matrix operations shared by all four rendering APIs. */
public final class MirrorMatrices {
    private MirrorMatrices() { }
    public static float[] multiply(float[] a, float[] b) {
        float[] c = new float[16];
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++)
            for (int k = 0; k < 4; k++) c[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
        return c;
    }
    public static double[] transform(float[] a, double x, double y, double z, double w) {
        return new double[] {a[0]*x+a[4]*y+a[8]*z+a[12]*w, a[1]*x+a[5]*y+a[9]*z+a[13]*w,
                a[2]*x+a[6]*y+a[10]*z+a[14]*w, a[3]*x+a[7]*y+a[11]*z+a[15]*w};
    }
    public static float[] inverse(float[] matrix) {
        double[][] a = new double[4][8];
        for (int row=0;row<4;row++) for(int col=0;col<4;col++) { a[row][col]=matrix[col*4+row]; a[row][col+4]=row==col?1:0; }
        for (int col=0;col<4;col++) {
            int pivot=col;
            for(int row=col+1;row<4;row++) if(Math.abs(a[row][col])>Math.abs(a[pivot][col])) pivot=row;
            if(Math.abs(a[pivot][col])<1e-12) throw new IllegalArgumentException("Singular view/projection");
            double[] swap=a[col];a[col]=a[pivot];a[pivot]=swap;
            double div=a[col][col];for(int k=0;k<8;k++) a[col][k]/=div;
            for(int row=0;row<4;row++) if(row!=col) { double scale=a[row][col];for(int k=0;k<8;k++) a[row][k]-=scale*a[col][k]; }
        }
        float[] out=new float[16];for(int row=0;row<4;row++) for(int col=0;col<4;col++) out[col*4+row]=(float)a[row][col+4];return out;
    }
    /** Clip everything behind the reflective surface. Supports both OpenGL and 26.x zero-to-one depth. */
    public static float[] oblique(float[] projection, float[] reflectedView, double nx, double ny, double nz,
                                  double cx, double cy, double cz, boolean zeroToOne) {
        float[] invView=inverse(reflectedView);
        double[] p={nx,ny,nz,-(nx*cx+ny*cy+nz*cz)-0.002};
        double[] eye=new double[4];
        for(int row=0;row<4;row++) for(int k=0;k<4;k++) eye[row]+=invView[row*4+k]*p[k];
        double[] q=transform(inverse(projection),Math.signum(eye[0]),Math.signum(eye[1]),1,1);
        double dot=eye[0]*q[0]+eye[1]*q[1]+eye[2]*q[2]+eye[3]*q[3];
        if(Math.abs(dot)<1e-8) return projection.clone();
        double scale=(zeroToOne?1:2)/dot;
        float[] out=projection.clone();
        for(int col=0;col<4;col++) out[col*4+2]=(float)(eye[col]*scale-(zeroToOne?0:projection[col*4+3]));
        return out;
    }
}
