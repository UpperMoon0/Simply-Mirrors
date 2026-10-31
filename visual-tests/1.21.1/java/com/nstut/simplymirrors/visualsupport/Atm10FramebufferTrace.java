package com.nstut.simplymirrors.visualsupport;

import com.nstut.simplymirrors.client.MirrorRenderer;
import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL33.*;

/** TEMPORARY ATM10 QA ONLY. Observe framebuffer transitions without changing GL state. */
public final class Atm10FramebufferTrace {
    private static final Map<String, Counter> seen = new HashMap<>();
    private Atm10FramebufferTrace() {}

    public static void check(String stage) {
        if (!Boolean.getBoolean("simplymirrors.atm10Sky") || MirrorRenderer.redirect == null) return;
        try {
            int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            int ds = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            int rs = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
            int db = glGetInteger(GL_DRAW_BUFFER);
            int rb = glGetInteger(GL_READ_BUFFER);
            String identity = draw + ":" + read + ":" + ds + ":" + rs + ":" + db + ":" + rb;
            Counter counter = seen.computeIfAbsent(stage, key -> new Counter());
            boolean broken = ds != GL_FRAMEBUFFER_COMPLETE || rs != GL_FRAMEBUFFER_COMPLETE;
            boolean changed = !identity.equals(counter.last);
            if (counter.printed >= 32
                    || (!broken && counter.printed >= 4 && !changed)
                    || (!broken && counter.printed >= 16)) return;
            counter.last = identity;
            counter.printed++;
            System.out.println("ATM10_SKY_FBO_CHECK " + stage
                    + " draw=" + draw + " read=" + read
                    + " drawStatus=" + ds + " readStatus=" + rs
                    + " drawBuffer=" + db + " readBuffer=" + rb
                    + " drawColor=" + attachment(GL_DRAW_FRAMEBUFFER, draw, GL_COLOR_ATTACHMENT0)
                    + " drawDepth=" + attachment(GL_DRAW_FRAMEBUFFER, draw, GL_DEPTH_ATTACHMENT)
                    + " readColor=" + attachment(GL_READ_FRAMEBUFFER, read, GL_COLOR_ATTACHMENT0)
                    + " readDepth=" + attachment(GL_READ_FRAMEBUFFER, read, GL_DEPTH_ATTACHMENT));
        } catch (RuntimeException failure) {
            System.out.println("ATM10_SKY_FBO_PROBE_ERROR " + stage + " " + failure);
        }
    }

    private static String attachment(int target, int framebuffer, int point) {
        if (framebuffer == 0) return "default";
        int type = glGetFramebufferAttachmentParameteri(target, point,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == GL_NONE) return "none";
        int name = glGetFramebufferAttachmentParameteri(target, point,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (type != GL_TEXTURE) return type + ":" + name;
        int level = glGetFramebufferAttachmentParameteri(target, point,
                GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
        return type + ":" + name + ":level=" + level;
    }

    private static final class Counter {
        String last = "";
        int printed;
    }
}
