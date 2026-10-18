"""Verify actual reflections on all six block faces with one isolated server/client session."""
from __future__ import annotations
import argparse, json, math, os, signal, socket, struct, subprocess, sys, time
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
TARGETS={t["target"]:t for t in json.loads((ROOT/"tools/targets.json").read_text())}
FACES=["north","south","west","east","up","down"]
NORMALS=[(0,0,-1),(0,0,1),(-1,0,0),(1,0,0),(0,1,0),(0,-1,0)]

def fixture(scene):
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
    # Every block retains the same legacy facing; the other five reflections must work independently of it.
    for x in range(-1,2):
        for y in range(65,68):
            pos=point(x,y,0);commands.append("setblock "+" ".join(map(str,pos))+" simply_mirrors:mirror[facing=north]")
    return commands

def scene_file(client_dir,scene):
    temporary=client_dir/"visual-scene.next";temporary.write_text(str(scene));temporary.replace(client_dir/"visual-scene.txt")

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

def image_check(path):
    image=Image.open(path).convert("RGB");w,h=image.size
    hits={"red":[],"green":[],"blue":[]}
    for y in range(int(h*.2),int(h*.8)):
        for x in range(int(w*.3),int(w*.7)):
            r,g,b=image.getpixel((x,y))
            if r>35 and r>g*1.8 and r>b*1.8:hits["red"].append(x)
            if g>35 and g>r*1.4 and g>b*1.3:hits["green"].append(x)
            if b>35 and b>r*1.8 and b>g*1.8:hits["blue"].append(x)
    for name,xs in hits.items():
        assert len(xs)>20,f"No reflected {name} marker in mirror: {len(xs)} pixels"
    red=sum(hits["red"])/len(hits["red"]);blue=sum(hits["blue"])/len(hits["blue"])
    assert red>blue+5,f"Reflection handedness incorrect: red x={red}, blue x={blue}"
    return {"pixels":{key:len(value) for key,value in hits.items()},"redMeanX":red,"blueMeanX":blue}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--target",choices=TARGETS,required=True);args=parser.parse_args()
    t=TARGETS[args.target];module=ROOT/args.target;cwd=ROOT/t["build_root"]
    port=free_port();rcon_port=free_port();server_dir=module/"run/visual-server";server_dir.mkdir(parents=True,exist_ok=True)
    if (server_dir/"visual-world").exists():raise SystemExit("Existing test world found; use a fresh checkout or preserve/rename the old test world before retrying")
    (server_dir/"eula.txt").write_text("eula=true\n")
    props={"server-ip":"127.0.0.1","server-port":port,"enable-rcon":"true","rcon.port":rcon_port,"rcon.password":"simply-mirrors-disposable-test","online-mode":"false","gamemode":"creative","force-gamemode":"true","allow-flight":"true","spawn-protection":0,"view-distance":4,"simulation-distance":4,"level-name":"visual-world","level-type":"flat" if t["minecraft"]=="1.16.5" else "minecraft:flat","max-players":2}
    (server_dir/"server.properties").write_text("\n".join(f"{k}={v}" for k,v in props.items())+"\n")
    command=[str(cwd/"gradlew.bat")] if os.name=="nt" else ["./gradlew"]
    if t["build_root"]==".":command.append(f"-Ptarget={args.target}")
    command += ["-PvisualTest",f"-PvisualMc={t['minecraft']}",f"-PvisualLoader={t['loader']}",f"-PvisualPort={port}","--no-daemon","--console=plain","-Dorg.gradle.jvmargs=-Xmx1024m","--max-workers=2"]
    task_prefix=f":{args.target}:" if t["build_root"]=="." else ""
    evidence=module/"run/visual-evidence";evidence.mkdir(parents=True,exist_ok=True)
    creation={"creationflags":subprocess.CREATE_NO_WINDOW} if os.name=="nt" else {"start_new_session":True}
    server=client=rcon=None
    with (evidence/"server.log").open("w") as server_log,(evidence/"client.log").open("w") as client_log:
        try:
            server=subprocess.Popen(command+[task_prefix+"runVisualServer"],cwd=cwd,stdout=server_log,stderr=subprocess.STDOUT,**creation)
            print("Starting disposable server",args.target,flush=True)
            deadline=time.monotonic()+360
            while time.monotonic()<deadline:
                if server.poll() is not None:raise RuntimeError(f"Server exited: {evidence/'server.log'}")
                try:rcon=Rcon(rcon_port);break
                except (OSError,ConnectionError):time.sleep(1)
            else:raise TimeoutError("Server startup timeout: "+(evidence/"server.log").read_text(errors="replace")[-8000:])
            print("Server and RCON ready",args.target,flush=True)
            commands=["forceload add -16 -16 16 16","time set noon","weather clear","fill -12 53 -12 12 53 12 stone","setworldspawn 0 65 -4"]+fixture(0)
            for text in commands:
                reply=rcon.command(text)
                if "Unknown" in reply or "Incorrect" in reply:raise RuntimeError(f"Fixture command failed: {text}: {reply}")
            print("Fixture ready; starting client",args.target,flush=True)
            client_dir=module/"run/visual-client";client_dir.mkdir(parents=True,exist_ok=True);scene_file(client_dir,0)
            client=subprocess.Popen(command+[task_prefix+"runVisualClient"],cwd=cwd,stdout=client_log,stderr=subprocess.STDOUT,**creation)
            results={}
            for scene,face in enumerate(FACES):
                if scene:
                    for text in fixture(scene):
                        reply=rcon.command(text)
                        if any(word in reply for word in ["Unknown","Incorrect","too many blocks"]):raise RuntimeError(f"Fixture failed: {text}: {reply}")
                    scene_file(client_dir,scene)
                result=client_dir/f"visual-evidence/result-{scene}.json";deadline=time.monotonic()+(600 if scene==0 else 180)
                while time.monotonic()<deadline and not result.exists():
                    if client.poll() is not None:raise RuntimeError(f"Client exited before {face} capture: {evidence/'client.log'}")
                    time.sleep(1)
                if not result.exists():raise TimeoutError(f"Client visual probe timed out on {face}: "+(evidence/"client.log").read_text(errors="replace")[-8000:])
                data=json.loads(result.read_text());assert data["passes"]>=120,data;assert data["angleFrames"]>=120,data;assert data["creativeTab"],data;assert data["glError"]==0,data
                data.update(image_check(result.with_name(f"mirror-{scene}.png")));results[face]=data
                print("Verified reflective face",args.target,face,json.dumps(data),flush=True)
            (evidence/"verified.json").write_text(json.dumps(results,indent=2)+"\n")
            scene_file(client_dir,6)
            # The probe quits normally after capture. Do not kill Gradle while it releases
            # Loom's cache lock, or accept a process that crashes after writing its result.
            exit_code=client.wait(timeout=60)
            if exit_code!=0:raise RuntimeError(f"Client failed after capture ({exit_code}): {evidence/'client.log'}")
            print("SIMPLY_MIRRORS_VISUAL_PASS",args.target,json.dumps(results),flush=True)
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

if __name__=="__main__":main()
