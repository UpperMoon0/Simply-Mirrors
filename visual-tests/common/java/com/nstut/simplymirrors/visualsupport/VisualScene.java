package com.nstut.simplymirrors.visualsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Camera transforms matching the six independently rebuilt RCON fixtures. */
public final class VisualScene {
    private VisualScene() { }
    public static int index(Path directory) throws Exception {
        return Integer.parseInt(new String(Files.readAllBytes(directory.resolve("visual-scene.txt")),StandardCharsets.UTF_8).trim());
    }
    public static double[] eye(int scene) {
        switch(scene) {
            case 0:return new double[]{.5,66.62,-3.5};
            case 1:return new double[]{.5,66.62,4.5};
            case 2:return new double[]{-3.5,66.62,.5};
            case 3:return new double[]{4.5,66.62,.5};
            case 4:return new double[]{.5,70.5,.62};
            case 5:return new double[]{.5,62.5,.38};
            default:throw new IllegalArgumentException("Unknown scene");
        }
    }
    public static float yaw(int scene) {return scene==1?180:scene==2?-90:scene==3?90:0;}
    public static float pitch(int scene) {return scene==4?90:scene==5?-90:0;}
}
