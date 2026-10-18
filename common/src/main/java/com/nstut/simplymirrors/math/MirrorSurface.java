package com.nstut.simplymirrors.math;

/** The complete outward-facing surface of a full mirror cube. */
public final class MirrorSurface {
    public final int x,y,z,nx,ny,nz;
    public final double cx,cy,cz;
    public MirrorSurface(int x,int y,int z,int nx,int nz) {
        this(x,y,z,nx,0,nz);
    }
    public MirrorSurface(int x,int y,int z,int nx,int ny,int nz) {
        if(Math.abs(nx)+Math.abs(ny)+Math.abs(nz)!=1)throw new IllegalArgumentException("Expected a cube-face normal");
        this.x=x;this.y=y;this.z=z;this.nx=nx;this.ny=ny;this.nz=nz;
        cx=x+.5+nx*.5;cy=y+.5+ny*.5;cz=z+.5+nz*.5;
    }
    public static MirrorSurface[] faces(int x,int y,int z) {
        return new MirrorSurface[]{new MirrorSurface(x,y,z,0,0,-1),new MirrorSurface(x,y,z,0,0,1),
            new MirrorSurface(x,y,z,-1,0,0),new MirrorSurface(x,y,z,1,0,0),
            new MirrorSurface(x,y,z,0,1,0),new MirrorSurface(x,y,z,0,-1,0)};
    }
    public MirrorPlane plane() { return new MirrorPlane(cx,cy,cz,nx,ny,nz); }
    public String planeKey() { return nx+":"+ny+":"+nz+":"+Math.round((nx*cx+ny*cy+nz*cz)*16); }
    public double[][] corners() {
        double ux=ny==0?-nz*.5:.5,uz=ny==0?nx*.5:0;
        double vy=ny==0?.5:0,vz=ny*.5;
        double bx=cx+nx*.001,by=cy+ny*.001,bz=cz+nz*.001;
        return new double[][] {{bx+ux,by-vy,bz+uz-vz},{bx-ux,by-vy,bz-uz-vz},
            {bx-ux,by+vy,bz-uz+vz},{bx+ux,by+vy,bz+uz+vz}};
    }
}
