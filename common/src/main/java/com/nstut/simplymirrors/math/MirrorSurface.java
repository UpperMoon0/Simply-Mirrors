package com.nstut.simplymirrors.math;

/** The complete outward-facing surface of a full mirror cube. */
public final class MirrorSurface implements MirrorGeometry {
    public final int x,y,z,nx,ny,nz;
    public final double cx,cy,cz;
    private final String planeKey;
    public MirrorSurface(int x,int y,int z,int nx,int nz) {
        this(x,y,z,nx,0,nz);
    }
    public MirrorSurface(int x,int y,int z,int nx,int ny,int nz) {
        if(Math.abs(nx)+Math.abs(ny)+Math.abs(nz)!=1)throw new IllegalArgumentException("Expected a cube-face normal");
        this.x=x;this.y=y;this.z=z;this.nx=nx;this.ny=ny;this.nz=nz;
        cx=x+.5+nx*.5;cy=y+.5+ny*.5;cz=z+.5+nz*.5;
        planeKey=nx+":"+ny+":"+nz+":"+Math.round((nx*cx+ny*cy+nz*cz)*16);
    }
    public static MirrorSurface[] faces(int x,int y,int z) {
        return new MirrorSurface[]{new MirrorSurface(x,y,z,0,0,-1),new MirrorSurface(x,y,z,0,0,1),
            new MirrorSurface(x,y,z,-1,0,0),new MirrorSurface(x,y,z,1,0,0),
            new MirrorSurface(x,y,z,0,1,0),new MirrorSurface(x,y,z,0,-1,0)};
    }
    public MirrorPlane plane() { return new MirrorPlane(cx,cy,cz,nx,ny,nz); }
    public String planeKey() { return planeKey; }
    /** Axis normals are already unit length; skip the temporary MirrorPlane allocation. */
    public boolean frontFacing(double eyeX,double eyeY,double eyeZ,double epsilon) {
        return (eyeX-cx)*nx+(eyeY-cy)*ny+(eyeZ-cz)*nz>epsilon;
    }
    public double[][] corners() {
        double[] c=new double[12];writeCorners(c);
        return new double[][]{{c[0],c[1],c[2]},{c[3],c[4],c[5]},
                {c[6],c[7],c[8]},{c[9],c[10],c[11]}};
    }
    private static void corner(double[] c,int i,double x,double y,double z) {
        int n=i*3;c[n]=x;c[n+1]=y;c[n+2]=z;
    }
    @Override public void writeCorners(double[] out) {
        double ux=ny==0?-nz*.5:.5,uz=ny==0?nx*.5:0;
        double vy=ny==0?.5:0,vz=ny*.5;
        double bx=cx+nx*.001,by=cy+ny*.001,bz=cz+nz*.001;
        corner(out,0,bx+ux,by-vy,bz+uz-vz);
        corner(out,1,bx-ux,by-vy,bz-uz-vz);
        corner(out,2,bx-ux,by+vy,bz-uz+vz);
        corner(out,3,bx+ux,by+vy,bz+uz+vz);
    }
}
