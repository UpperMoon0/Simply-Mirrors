package com.nstut.simplymirrors.visualsupport;
import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33.*;
public final class VisualCapture {
    public static void save(int texture,int width,int height,Path path) throws Exception {
        int oldRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),oldPack=glGetInteger(GL_PACK_ALIGNMENT);
        int fbo=glGenFramebuffers();
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);glPixelStorei(GL_PACK_ALIGNMENT,1);
            ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){int i=(y*width+x)*4;image.setRGB(x,height-y-1,0xff000000|(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|(pixels.get(i+2)&255));}
            Files.createDirectories(path.getParent());ImageIO.write(image,"png",path.toFile());
        }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead);glPixelStorei(GL_PACK_ALIGNMENT,oldPack);glDeleteFramebuffers(fbo);}
    }
}
