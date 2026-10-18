package com.nstut.simplymirrors.math;

/** The complete outward-facing surface of a full mirror cube. */
public final class MirrorSurface {
    public final int x,y,z,nx,nz;
    public final double cx,cy,cz;
    public MirrorSurface(int x,int y,int z,int nx,int nz) {
        this.x=x;this.y=y;this.z=z;this.nx=nx;this.nz=nz;
        cx=x+.5+nx*.5;cy=y+.5;cz=z+.5+nz*.5;
    }
    public MirrorPlane plane() { return new MirrorPlane(cx,cy,cz,nx,0,nz); }
    public String planeKey() { return nx+":"+nz+":"+Math.round((nx*cx+nz*cz)*16); }
    public double[][] corners() {
        double rightX=-nz*.5,rightZ=nx*.5;
        return new double[][] {{cx+rightX+nx*.001,cy-.5,cz+rightZ+nz*.001},{cx-rightX+nx*.001,cy-.5,cz-rightZ+nz*.001},
            {cx-rightX+nx*.001,cy+.5,cz-rightZ+nz*.001},{cx+rightX+nx*.001,cy+.5,cz+rightZ+nz*.001}};
    }
}
