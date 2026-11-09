"""Verify actual reflections on all six block faces with one isolated server/client session."""
from __future__ import annotations
import argparse, json, math, os, re, shutil, signal, socket, struct, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TARGETS={t["target"]:t for t in json.loads((ROOT/"tools/targets.json").read_text())}
FACES=["north","south","west","east","up","down"]
NORMALS=[(0,0,-1),(0,0,1),(-1,0,0),(1,0,0),(0,1,0),(0,-1,0)]
POSES=[(.5,65,-3.5,0,0),(.5,65,4.5,180,0),(-3.5,65,.5,-90,0),
       (4.5,65,.5,90,0),(.5,68.88,.62,0,90),(.5,60.88,.38,0,-90)]

def fixture(scene, enclosed=False):
    nx,ny,nz=NORMALS[scene]
    right=(-nz,0,nx) if not ny else (1,0,0)
    up=(0,1,0) if not ny else (0,0,ny)
    def point(x,y,z):
        local=(x+.5-.5,y+.5-66.5,-(z+.5-.5))
        return tuple(math.floor(c+right[i]*local[0]+up[i]*local[1]+NORMALS[scene][i]*local[2]+1e-7) for i,c in enumerate((.5,66.5,.5)))
    def fill(a,b,block):
        a,b=point(*a),point(*b)
        low=[min(x,y) for x,y in zip(a,b)];high=[max(x,y) for x,y in zip(a,b)]
        return "fill "+" ".join(map(str,low+high))+" "+block
    commands=["fill -12 54 -12 12 79 12 air",fill((-5,65,1),(5,70,1),"white_concrete"),
              fill((-4,65,-8),(-2,69,-8),"red_concrete"),fill((-1,65,-8),(1,69,-8),"lime_concrete"),
              fill((2,65,-8),(4,69,-8),"blue_concrete")]
    if enclosed:
        # Issue #5 reports an indoor mirror. Dense room walls catch a class of
        # chunk/occlusion regressions that the original open fixture can miss.
        # The outer opaque wall behind the mirror remains a clipping obstacle.
        commands.extend([
            fill((-9,63,-10),(9,63,0),"stone"),
            fill((-9,73,-10),(9,73,0),"stone"),
            fill((-9,63,-10),(-9,73,0),"stone"),
            fill((9,63,-10),(9,73,0),"stone"),
            fill((-9,63,-10),(9,73,-10),"stone"),
            fill((-9,63,0),(9,73,0),"white_concrete"),
            # Opaque walls and roof remove skylight entirely. Real shader packs
            # correctly expose a sealed, unlit room nearly black: that cannot
            # be used to distinguish a sky-only mirror from dark geometry.
            # Illuminate the colored reflection targets with block light while
            # keeping the enclosing roof and side walls opaque.
            fill((-7,72,-8),(7,72,-8),"sea_lantern"),
            fill((-7,72,-5),(7,72,-5),"sea_lantern"),
            fill((-7,72,-2),(7,72,-2),"sea_lantern")
        ])
    # Every block retains the same legacy facing; the other five reflections must work independently of it.
    for x in range(-1,2):
        for y in range(65,68):
            pos=point(x,y,0);commands.append("setblock "+" ".join(map(str,pos))+" simply_mirrors:mirror[facing=north]")
    return commands

def scene_file(client_dir,scene):
    temporary = client_dir / "visual-scene.next"
    destination = client_dir / "visual-scene.txt"
    temporary.write_text(str(scene))
    # Retry temporary Windows sharing violations from the JVM scene reader.
    # Keep the previous scene file intact until the atomic replacement works.
    for attempt in range(12):
        try:
            temporary.replace(destination)
            return
        except PermissionError:
            if attempt == 11:
                raise
            time.sleep(0.05)

def activate_scene(rcon,client_dir,scene,client,timeout=600):
    # Do not let the client pose a new scene until the server has authorized its
    # position. Client-only teleports can trigger movement corrections mid-capture.
    command="tp @a "+" ".join(map(str,POSES[scene]))
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if client.poll() is not None:raise RuntimeError("Client exited before scene activation")
        reply=rcon.command(command)
        if "Teleported" in reply:
            scene_file(client_dir,scene)
            return
        if "Incorrect" in reply or "Unknown" in reply:raise RuntimeError(f"Scene teleport failed: {reply}")
        time.sleep(1)
    raise TimeoutError(f"No player joined for scene {FACES[scene]}")

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0));return sock.getsockname()[1]

def exact(sock,size):
    result=b""
    while len(result)<size:
        part=sock.recv(size-len(result))
        if not part:raise ConnectionError("RCON closed")
        result+=part
    return result

class Rcon:
    def __init__(self,port):
        self.sock=socket.create_connection(("127.0.0.1",port),timeout=5)
        self.packet(3,"simply-mirrors-disposable-test")
    def packet(self,kind,text):
        data=struct.pack("<ii",17,kind)+text.encode()+b"\0\0"
        self.sock.sendall(struct.pack("<i",len(data))+data)
        length=struct.unpack("<i",exact(self.sock,4))[0];reply=exact(self.sock,length)
        if struct.unpack("<i",reply[:4])[0]==-1:raise RuntimeError("RCON authentication failed")
        return reply[8:-2].decode(errors="replace")
    def command(self,text):return self.packet(2,text)
    def close(self):self.sock.close()


def stop(process):
    if process is None or process.poll() is not None:return
    if os.name=="nt":subprocess.run(["taskkill","/PID",str(process.pid),"/T","/F"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    else:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL)

def image_check(path,shaders=False,require_entity=False,face_index=0):
    from PIL import Image
    import colorsys
    image=Image.open(path).convert("RGB");w,h=image.size
    hits={"red":[],"green":[],"blue":[]}
    # The centered fixture's marker panels occupy this inset of the mirror.
    # Exclude the blue sky and surrounding shaded wall from shader band centroids.
    left,right,top,bottom=(.35,.65,.3,.6) if shaders else (.3,.7,.2,.8)
    for y in range(int(h*top),int(h*bottom)):
        for x in range(int(w*left),int(w*right)):
            r,g,b=image.getpixel((x,y))
            if shaders:
                # Shader lighting changes brightness and warms lime toward yellow.
                # Require substantial chroma and all three ordered marker bands.
                hue,saturation,value=colorsys.rgb_to_hsv(r/255,g/255,b/255)
                hue*=360
                if saturation<.28 or value<.08 or max(r,g,b)-min(r,g,b)<8:continue
                if hue<30 or hue>300:hits["red"].append(x)
                if 65<hue<165:hits["green"].append(x)
                if 190<hue<265:hits["blue"].append(x)
            else:
                if r>35 and r>g*1.8 and r>b*1.8:hits["red"].append(x)
                if g>35 and g>r*1.4 and g>b*1.3:hits["green"].append(x)
                if b>35 and b>r*1.8 and b>g*1.8:hits["blue"].append(x)
    for name,xs in hits.items():
        assert len(xs)>(w*h*.003 if shaders else 20),f"No reflected {name} marker in mirror: {len(xs)} pixels"
    red=sum(hits["red"])/len(hits["red"]);blue=sum(hits["blue"])/len(hits["blue"])
    assert red>blue+5,f"Reflection handedness incorrect: red x={red}, blue x={blue}"
    if shaders:
        green=sum(hits["green"])/len(hits["green"])
        assert blue+20<green<red-20,"Reflected marker bands are not ordered blue, green, red"
    result={"pixels":{key:len(value) for key,value in hits.items()},"redMeanX":red,"blueMeanX":blue}
    if require_entity:
        if not shaders:
            raise ValueError("Reflected entity assertion requires shaders")
        # A colored wall alone passed in issue #5 when the reflected entity
        # was missing. This is only for the pinned NeoForge enclosed fixture.
        # Normalized bounds below match its independently captured PNGs.
        def non_lime(x0,x1,y0,y1):
            count=samples=0
            for yy in range(int(h*y0/270),int(h*y1/270)):
                for xx in range(int(w*x0/480),int(w*x1/480)):
                    rr,gg,bb=image.getpixel((xx,yy))
                    samples+=1
                    if not (gg>rr*1.12 and gg>bb*1.07):count+=1
            return count,samples
        side,side_count=non_lime(253,263,100,140)
        # A ceiling reflection shows the model sideways and can obscure the
        # adjacent marker; a floor reflection makes it smaller. The fixed
        # cardinal threshold must not falsely reject these vertical poses.
        if face_index != 4:
            assert side*10<side_count*2,"Reflected center panel cannot be confirmed green"
        occupied,occupied_count=non_lime(232,249,93,144)
        needed=(occupied_count*0.16 if face_index==4 else
                occupied_count*0.045 if face_index==5 else occupied_count*0.10)
        assert occupied>=needed,"No reflected player silhouette against center green marker"
        result["entityPixels"]=occupied
    return result

def motion_check(idle_path, moving_path):
    """Confirm the reflected player visibly changes during a deliberate swing."""
    from PIL import Image,ImageChops
    with Image.open(idle_path) as first,Image.open(moving_path) as second:
        idle=first.convert("RGB")
        moving=second.convert("RGB")
        assert idle.size==moving.size,"Motion screenshots have different resolutions"
        w,h=idle.size
        roi=(int(w*0.45),int(h*0.28),int(w*0.55),int(h*0.66))
        difference=ImageChops.difference(idle.crop(roi),moving.crop(roi))
        changing=sum(max(pixel)>35 for pixel in difference.getdata())
        threshold=max(80,int((roi[2]-roi[0])*(roi[3]-roi[1])*0.006))
        if changing<=threshold:
            # Distinguish a misframed ROI from a completed animation. Full-frame
            # differences are diagnostic only, never accepted as motion proof.
            full=ImageChops.difference(idle,moving)
            bounds=full.getbbox()
            full_changed=sum(max(pixel)>35 for pixel in full.getdata())
            raise AssertionError(
                f"Reflected player swing did not change sufficient framebuffer pixels: "
                f"roi={changing}, threshold={threshold}, fullFrame={full_changed}, "
                f"diffBounds={bounds}, roiBounds={roi}")
        return {"movingEntityPixels":changing}


def camera_motion_check(idle_path,moving_path,settled_path):
    """Quantify pan/strafe movement and bound reflected ghosting at the same target pose.

    This is a GPU framebuffer acceptance check with actual Complementary enabled,
    not a synthetic matrix test. The settled frame serves as a sharp reference at
    the moving frame's position. Keep thresholds broad for differing Mesa hosts.
    """
    from PIL import Image, ImageChops, ImageStat
    images=[]
    for p in (idle_path,moving_path,settled_path):
        with Image.open(p) as image:
            images.append(image.convert("RGB"))
    idle,moving,settled=images
    assert idle.size==moving.size==settled.size,"Camera-motion frames differ in resolution"
    w,h=idle.size
    roi=(int(w*.30),int(h*.24),int(w*.70),int(h*.75))
    baseline=idle.crop(roi)
    moving=moving.crop(roi)
    settled=settled.crop(roi)
    changed=ImageChops.difference(baseline,settled)
    actual_motion=sum(max(pixel)>15 for pixel in changed.getdata())
    assert actual_motion>max(200,int((roi[2]-roi[0])*(roi[3]-roi[1])*.006)), (
        f"Camera pan/strafe produced no meaningful reflected pixel displacement: {actual_motion}")
    temporal=ImageChops.difference(moving,settled)
    average=sum(ImageStat.Stat(temporal).mean)/3
    # A reflected frame may contain legitimate motion blur and temporal shimmer.
    # Almost full-frame contamination indicates pathological false velocity.
    assert average<55, f"Excessive Complementary camera blur/ghosting after pan/strafe: MAD={average:.2f}"
    return {"cameraMovedPixels":actual_motion,"cameraMotionVsSettledMeanAbsDifference":round(average,3)}


def fixture_java_processes(client_dir, proc_root=Path("/proc")):
    """Find the disposable client JVM, including launches with a cwd-only run dir.

    Gradle/Loom passes the game directory as the process working directory,
    not necessarily as a JVM command-line argument. Both matches are strictly
    scoped to this exact disposable directory; other Minecraft clients must
    never be inspected.
    """
    target = client_dir.resolve()
    target_arg = os.fsencode(str(target))
    try:
        processes = sorted((entry for entry in proc_root.iterdir()
                            if entry.name.isdecimal()), key=lambda entry: int(entry.name))
    except OSError:
        return []
    matches = []
    for entry in processes:
        try:
            command = (entry / "cmdline").read_bytes()
        except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
            continue
        # Match the Java executable, not a non-Java process mentioning it
        # among its arguments (e.g. a Python test launcher).
        executable = command.split(bytes([0]), 1)[0].rsplit(b"/", 1)[-1].lower()
        if executable not in (b"java", b"javaw"):
            continue
        try:
            same_cwd = (entry / "cwd").resolve(strict=True) == target
        except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
            same_cwd = False
        # An argv substring is unsafe: "visual-client-other" must not match
        # "visual-client". Accept only an exact path or a -Dkey=path value.
        explicit_path = any(arg == target_arg or
                            (arg.startswith(b"-D") and arg.endswith(b"=" + target_arg))
                            for arg in command.split(bytes([0])))
        if same_cwd or explicit_path:
            matches.append((entry.name, command))
    return matches


def capture_client_thread_dump(client_dir, evidence):
    """Record this disposable client's JVM threads without attaching to other games."""
    if os.name != "posix" or not Path("/proc").is_dir():
        return
    jcmd = shutil.which("jcmd")
    report = evidence / "client-thread-dump.txt"
    if not jcmd:
        report.write_text("jcmd not installed; could not inspect visual fixture.\n")
        return
    lines = []
    try:
        for pid, command in fixture_java_processes(client_dir):
            lines.append(f"=== client JVM {pid} ({command[:700]!r}) ===\n")
            try:
                dump = subprocess.run([jcmd, pid, "Thread.print", "-l"],
                        capture_output=True, text=True, timeout=12, check=False)
                lines.append(f"exit={dump.returncode}\n{dump.stdout[-180000:]}\n{dump.stderr[-5000:]}\n")
            except (OSError, subprocess.TimeoutExpired) as error:
                lines.append(f"Thread dump unavailable: {error!r}\n")
        report.write_text("".join(lines) if lines else
                "No running fixture Java process matched the exact client directory.\n")
    except BaseException as error:
        report.write_text(f"Client diagnostic collection failed: {error!r}\n")


def finish_capture(client_dir,evidence,results,failures,client):
    # Preserve diagnostic captures even when shutdown or image acceptance fails.
    (evidence/"results.json").write_text(json.dumps(results,indent=2)+"\n")
    scene_file(client_dir,6)
    # Gradle must release its cache lock and exit cleanly before success is recorded.
    exit_code=client.wait(timeout=60)
    if exit_code!=0:raise RuntimeError(f"Client failed after capture ({exit_code}): {evidence/'client.log'}")
    if failures:raise AssertionError("Reflection image checks failed: "+json.dumps(failures))
    (evidence/"verified.json").write_text(json.dumps(results,indent=2)+"\n")

def install_extra_client_mods(client_dir, jars):
    """Reproduce shader/modpack interactions without touching the real instance."""
    import hashlib
    import zipfile
    import tempfile
    target = client_dir / "mods"
    names = set()
    validated = []
    for original in jars:
        path = Path(original).resolve(strict=True)
        if path.suffix.lower() != ".jar" or not path.is_file() or not zipfile.is_zipfile(path):
            raise ValueError(f"Not a Minecraft mod JAR: {path}")
        if path.name in names or (target / path.name).exists():
            raise ValueError(f"Duplicate extra mod filename: {path.name}")
        names.add(path.name)
        validated.append(path)
    if not validated:
        return []
    client_dir.mkdir(parents=True, exist_ok=True)
    found = []
    # Stage all files before publishing any to mods.
    with tempfile.TemporaryDirectory(prefix=".extra-mods-", dir=client_dir) as staging:
        staged = Path(staging)
        for path in validated:
            copy = staged / path.name
            shutil.copy2(path, copy)
            found.append({"file": path.name, "sha256": hashlib.sha256(copy.read_bytes()).hexdigest()})
        target.mkdir(parents=True, exist_ok=True)
        installed = []
        try:
            for path in validated:
                destination = target / path.name
                # Exclusive creation avoids replacing a concurrent profile JAR.
                with destination.open("xb") as output, (staged / path.name).open("rb") as source:
                    installed.append(destination)
                    shutil.copyfileobj(source, output)
        except BaseException:
            for destination in installed:
                destination.unlink(missing_ok=True)
            raise
    return found



def prepare_visual_client_assets(command, task, cwd, evidence, attempts=3):
    """Resolve Loom assets before starting the server; retry only download failures."""
    for attempt in range(1, attempts + 1):
        log = evidence / f"asset-download-{attempt}.log"
        with log.open("w") as output:
            result = subprocess.run(command + [task], cwd=cwd, stdout=output,
                                    stderr=subprocess.STDOUT, check=False)
        if result.returncode == 0:
            return
        # A broken build, missing task, or compile failure must not be
        # obscured by retries. Each attempt has its own preserved log.
        detail = log.read_text(errors="replace")
        transient = "net.fabricmc.loom.util.download.DownloadException" in detail
        if not transient or attempt == attempts:
            raise RuntimeError(
                f"Visual client asset preflight failed (attempt {attempt}/{attempts}, "
                f"exit {result.returncode}): {log}")
        time.sleep(3 * attempt)


def prepare_visual_server_build(command, task, cwd, evidence, timeout=900):
    """Bound the cold Gradle compile/remap phase independently of RCON readiness."""
    log = evidence / "server-preparation.log"
    try:
        with log.open("w") as output:
            result = subprocess.run(command + [task], cwd=cwd, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        raise TimeoutError(f"Visual server build preparation exceeded {timeout}s: {log}") from error
    if result.returncode != 0:
        raise RuntimeError(f"Visual server build preparation failed ({result.returncode}): " +
                           log.read_text(errors="replace")[-8000:])
    print(f"Server build prepared: {task}", flush=True)


def latest_visual_heartbeat(log, scene):
    """Return progress only for the active scene, never a previous face."""
    if not log.exists():
        return None
    # Read a bounded tail to avoid growing work as the game log expands.
    with log.open("rb") as stream:
        stream.seek(0, os.SEEK_END)
        size = stream.tell()
        stream.seek(max(0, size - 65536))
        tail = stream.read().decode("utf-8", errors="replace")
    pattern = rf"SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene={scene} ticks=(\d+) reflectionPasses=(\d+)"
    matches = list(re.finditer(pattern, tail))
    return tuple(map(int, matches[-1].groups())) if matches else None


def wait_for_visual_face(result, face, scene, client, client_dir, evidence, client_log):
    """Extend only when genuine render progress is observed; retain a hard cutoff."""
    started = time.monotonic()
    initial = 600 if scene == 0 else 180
    hard_deadline = started + (780 if scene == 0 else 420)
    deadline = started + initial
    latest_tick = 0
    latest_passes = 0
    last_progress = started
    diagnostic_captured = False
    while time.monotonic() < min(deadline, hard_deadline) and not result.exists():
        if client.poll() is not None:
            raise RuntimeError(f"Client exited before {face} capture: {client_log}")
        now = time.monotonic()
        heartbeat = latest_visual_heartbeat(client_log, scene)
        if heartbeat and (heartbeat[0] > latest_tick or heartbeat[1] > latest_passes):
            latest_tick, latest_passes = heartbeat
            last_progress = now
            # Slow software GL must not be mistaken for a deadlock. Each
            # fresh heartbeat grants at most 100 more seconds, within hard cap.
            deadline = min(hard_deadline, max(deadline, now + 100))
        if latest_tick > 0 and now - last_progress > 125:
            break
        if scene == 0 and not diagnostic_captured and now - started >= 180:
            capture_client_thread_dump(client_dir, evidence)
            diagnostic_captured = True
        time.sleep(1)
    if not result.exists():
        if not diagnostic_captured:
            capture_client_thread_dump(client_dir, evidence)
        raise TimeoutError(
            f"Client visual probe timed out on {face} (ticks={latest_tick}, "
            f"passes={latest_passes}, seconds_since_heartbeat="
            f"{time.monotonic()-last_progress:.1f}): " +
            client_log.read_text(errors="replace")[-8000:])


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--target",choices=TARGETS,required=True)
    parser.add_argument("--shaders",action="store_true",help="Enable and verify the locked Complementary shader stack")
    parser.add_argument("--run-name",help="Isolated test directory prefix")
    parser.add_argument("--shader-pack", type=Path, help="Use an exact local shaderpack ZIP instead of the pinned default")
    parser.add_argument("--interactive-offscreen", action="store_true",
                        help="Use the separate native Windows CI graphics desktop; never inject into another session")
    parser.add_argument("--shader-cache", type=Path, default=ROOT/"build/shader-dependencies",
                        help="Verified shader dependency cache location")
    parser.add_argument("--enclosed", action="store_true", help="Build an indoor mirror room (issue #5 regression)")
    parser.add_argument("--check-motion", action="store_true",
                        help="Animate a reflected player swing and verify two real framebuffer captures")
    parser.add_argument("--check-camera-motion", action="store_true",
                        help="Capture a reflected camera strafe/pan with pinned Complementary TAA and motion blur enabled")
    parser.add_argument("--force-stencil", action="store_true",
                        help="Exercise NeoForge main framebuffer stencil as enabled by Immersive Engineering")
    parser.add_argument("--require-entity", action="store_true",
                        help="Require a player silhouette in the NeoForge 1.21.1 shader reflection")
    parser.add_argument("--extra-client-mod", type=Path, action="append", default=[],
                        help="Copy one optional rendering/modpack JAR into the isolated client only (repeatable)")

    args=parser.parse_args()
    if args.interactive_offscreen and (os.name != "nt" or os.environ.get("CI_GRAPHICS_AGENT") != "1"):
        parser.error("--interactive-offscreen requires a reviewed native Windows graphics agent")
    if args.check_motion and (args.target!="neoforge-1.21.1" or not args.enclosed or not args.shaders):
        parser.error("--check-motion requires NeoForge 1.21.1 enclosed shader validation")
    if args.check_camera_motion and (args.target!="neoforge-1.21.1" or not args.shaders):
        parser.error("--check-camera-motion requires NeoForge 1.21.1 shader validation")
    if args.force_stencil and (args.target!="neoforge-1.21.1" or not args.enclosed or not args.shaders):
        parser.error("--force-stencil is for NeoForge 1.21.1 enclosed shader validation")
    if args.require_entity and (args.target!="neoforge-1.21.1" or not args.enclosed or not args.shaders):
        parser.error("--require-entity is for NeoForge 1.21.1 enclosed shader validation")
    t=TARGETS[args.target];module=ROOT/args.target;cwd=ROOT/t["build_root"]
    run_name=args.run_name or ("shader" if args.shaders else "visual")
    if not __import__('re').fullmatch('[a-z0-9-]+',run_name):raise ValueError('Invalid run name')
    client_dir=module/f"run/{run_name}-client"
    if args.shader_pack and not args.shaders:
        parser.error("--shader-pack requires --shaders")
    if args.shaders:
        from shader_stack import prepare, install_pack
        _,pinned_pack=prepare(args.target,args.shader_cache.resolve())
        pack=args.shader_pack.resolve(strict=True) if args.shader_pack else pinned_pack
        if pack.suffix.lower() != ".zip":
            parser.error("--shader-pack must be a shaderpack ZIP")
        install_pack(client_dir,pack,loader=t['loader'])
        if args.check_camera_motion:
            # A disposable shaderpack profile, never applied to the user's game.
            # Complementary r5.9.3's exact options are pinned in the shader ZIP.
            (client_dir/"shaderpacks"/(pack.name+".txt")).write_text(
                "MOTION_BLUR_EFFECT=1\nMOTION_BLURRING_STRENGTH=1.00\n"
                "TAA_DEFINE=1\nTAA_SMOOTHING=3\n")
    # Native mods are copied only into the disposable client profile, never
    # the production game directory or server. Keep a reproducible hash ledger.
    extra_mods=install_extra_client_mods(client_dir,args.extra_client_mod)
    port=free_port();rcon_port=free_port();server_dir=module/f"run/{run_name}-server";server_dir.mkdir(parents=True,exist_ok=True)
    if (server_dir/"visual-world").exists():raise SystemExit("Existing test world found; use a fresh checkout or preserve/rename the old test world before retrying")
    (server_dir/"eula.txt").write_text("eula=true\n")
    props={"server-ip":"127.0.0.1","server-port":port,"enable-rcon":"true","rcon.port":rcon_port,"rcon.password":"simply-mirrors-disposable-test","online-mode":"false","gamemode":"creative","force-gamemode":"true","allow-flight":"true","spawn-protection":0,"view-distance":4,"simulation-distance":4,"level-name":"visual-world","level-type":"flat" if t["minecraft"]=="1.16.5" else "minecraft:flat","max-players":2}
    (server_dir/"server.properties").write_text("\n".join(f"{k}={v}" for k,v in props.items())+"\n")
    command=[str(cwd/"gradlew.bat")] if os.name=="nt" else ["./gradlew"]
    if t["build_root"]==".":command.append(f"-Ptarget={args.target}")
    command += ["-PvisualTest",f"-PvisualMc={t['minecraft']}",f"-PvisualLoader={t['loader']}",f"-PvisualPort={port}","--no-daemon","--console=plain","-Dorg.gradle.jvmargs=-Xmx1024m","--max-workers=2"]
    command += [f"-PvisualRunName={run_name}",f"-PmirrorsShaderStack={args.shader_cache.resolve() if args.shaders else 'off'}"]
    task_prefix=f":{args.target}:" if t["build_root"]=="." else ""
    evidence=module/f"run/{run_name}-evidence";evidence.mkdir(parents=True,exist_ok=True)
    (evidence/"extra-client-mods.json").write_text(json.dumps(extra_mods,indent=2))
    # A Loom asset fetch can fail before the game starts. Retry that download
    # before bringing up the disposable server, not a crashed game client.
    if args.target == "fabric-1.16.5":
        prepare_visual_client_assets(command, task_prefix+"downloadAssets", cwd, evidence)
    if args.shaders:
        import hashlib
        (evidence/"shaderpack.json").write_text(json.dumps({"name":pack.name,"sha256":hashlib.sha256(pack.read_bytes()).hexdigest()},indent=2))
    creation={"creationflags":subprocess.CREATE_NO_WINDOW} if os.name=="nt" else {"start_new_session":True}
    server=client=rcon=None
    server_command=["-PmirrorsShaderStack=off" if item.startswith("-PmirrorsShaderStack=") else item for item in command]
    prepare_visual_server_build(server_command,task_prefix+"classes",cwd,evidence)
    with (evidence/"server.log").open("w") as server_log,(evidence/"client.log").open("w") as client_log:
        try:
            server=subprocess.Popen(server_command+[task_prefix+"runVisualServer"],cwd=cwd,stdout=server_log,stderr=subprocess.STDOUT,**creation)
            print("Starting disposable server",args.target,flush=True)
            deadline=time.monotonic()+360
            while time.monotonic()<deadline:
                if server.poll() is not None:raise RuntimeError(f"Server exited: {evidence/'server.log'}")
                try:rcon=Rcon(rcon_port);break
                except (OSError,ConnectionError):time.sleep(1)
            else:raise TimeoutError("Server startup timeout: "+(evidence/"server.log").read_text(errors="replace")[-8000:])
            print("Server and RCON ready",args.target,flush=True)
            clock_rule="advance_time" if t["minecraft"]=="26.1.2" else "doDaylightCycle"
            weather_rule="advance_weather" if t["minecraft"]=="26.1.2" else "doWeatherCycle"
            commands=[f"gamerule {clock_rule} false",f"gamerule {weather_rule} false","forceload add -16 -16 16 16","time set noon","weather clear","fill -12 53 -12 12 53 12 stone","setworldspawn 0 65 -4"]+fixture(0,args.enclosed)
            for text in commands:
                reply=rcon.command(text)
                if "Unknown" in reply or "Incorrect" in reply:raise RuntimeError(f"Fixture command failed: {text}: {reply}")
            print("Fixture ready; starting client",args.target,flush=True)
            client_dir.mkdir(parents=True,exist_ok=True);scene_file(client_dir,-1)
            client_env=os.environ.copy()
            if args.interactive_offscreen:
                # Jenkins already runs in a restricted, isolated private desktop.
                # Inherit that desktop directly; do not use WTS tokens or
                # CreateProcessAsUser to access the normal user desktop.
                print("SIMPLY_MIRRORS_VISUAL_PRIVATE_DESKTOP", flush=True)
                client_env["JAVA_TOOL_OPTIONS"]=(client_env.get("JAVA_TOOL_OPTIONS", "") +
                                                  " -Dsimplymirrors.visualOffscreen=true").strip()
            if args.check_camera_motion:
                client_env["JAVA_TOOL_OPTIONS"]=(client_env.get("JAVA_TOOL_OPTIONS","")+
                                                   " -Dsimplymirrors.visualCheckCameraMotion=true").strip()
            if args.check_motion:
                client_env["JAVA_TOOL_OPTIONS"]=(client_env.get("JAVA_TOOL_OPTIONS","")+
                                                   " -Dsimplymirrors.visualCheckMotion=true").strip()
            if args.force_stencil:
                # Only the disposable client gets the opt-in stencil trigger.
                # The fixture server and all production/runtime jars are untouched.
                client_env["JAVA_TOOL_OPTIONS"]=(client_env.get("JAVA_TOOL_OPTIONS","")+
                                                   " -Dsimplymirrors.visualForceStencil=true").strip()
            # The graphics worker is already a restricted interactive
            # process in its dedicated private desktop. Inherit that exact
            # identity, rather than brokering the logged-in user's WTS token.
            client=subprocess.Popen(command+[task_prefix+"runVisualClient"],cwd=cwd,
                                    stdout=client_log,stderr=subprocess.STDOUT,
                                    env=client_env,**creation)
            if args.interactive_offscreen:
                print("SIMPLY_MIRRORS_VISUAL_ACTIVE_GPU_CLIENT pid="+str(client.pid),flush=True)
            results={};failures={}
            for scene,face in enumerate(FACES):
                if scene:
                    for text in fixture(scene,args.enclosed):
                        reply=rcon.command(text)
                        if any(word in reply for word in ["Unknown","Incorrect","too many blocks"]):raise RuntimeError(f"Fixture failed: {text}: {reply}")
                activate_scene(rcon,client_dir,scene,client)
                result=client_dir/f"visual-evidence/result-{scene}.json"
                client_log_path=evidence/"client.log"
                wait_for_visual_face(result,face,scene,client,client_dir,evidence,client_log_path)
                data=json.loads(result.read_text());assert data["passes"]>=120,data;assert data["angleFrames"]>=120,data;assert data["creativeTab"],data;assert data["glError"]==0,data
                if args.shaders:assert data.get('shaderPackActive') is True,data
                if args.force_stencil:assert data.get('stencilEnabled') is True,data
                if args.check_motion and scene==0:assert data.get('motionCaptured') is True,data
                if args.check_camera_motion and scene==0:assert data.get('cameraMotionCaptured') is True,data
                try:
                    data.update(image_check(result.with_name(f"mirror-{scene}.png"),shaders=args.shaders,
                                            require_entity=args.require_entity,face_index=scene))
                    if args.check_motion and scene==0:
                        data.update(motion_check(result.with_name("mirror-motion-idle.png"),
                                                 result.with_name("mirror-motion-swing.png")))
                    if args.check_camera_motion and scene==0:
                        data.update(camera_motion_check(result.with_name("mirror-camera-idle.png"),
                                                        result.with_name("mirror-camera-moving.png"),
                                                        result.with_name("mirror-camera-settled.png")))
                except AssertionError as error:
                    failures[face]=str(error);data['imageError']=str(error)
                results[face]=data
                print("Failed reflective face" if face in failures else "Verified reflective face",args.target,face,json.dumps(data),flush=True)
            finish_capture(client_dir,evidence,results,failures,client)
            print("SIMPLY_MIRRORS_VISUAL_PASS",args.target,json.dumps(results),flush=True)
        except BaseException as error:
            (evidence/"failure.txt").write_text(f"{type(error).__name__}: {error}\n")
            print("SIMPLY_MIRRORS_VISUAL_FAIL",args.target,repr(error),flush=True)
            raise
        finally:
            stop(client)
            if rcon:
                try:rcon.command("stop")
                except OSError:pass
                rcon.close()
                if server:
                    try:server.wait(timeout=30)
                    except subprocess.TimeoutExpired:pass
            stop(server)
            if args.interactive_offscreen:
                from fps_benchmark import cleanup_owned_gradle_servers
                cleanup_owned_gradle_servers(cwd,run_name)

if __name__=="__main__":main()
