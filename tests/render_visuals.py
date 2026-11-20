#!/usr/bin/env python3
"""Render the actual app shaders and Robolectric-exported Earth mesh using surfaceless EGL/GLES3.
Ubuntu CI: libegl1, libgles2, ffmpeg. No surrogate sphere/image is used for Earth.
Outputs PNG previews and machine-checked landmark samples in build/visuals.
"""
import os, ctypes as C, ctypes.util, struct, math, re, pathlib, subprocess, json, zlib, hashlib
os.environ.setdefault('EGL_PLATFORM','surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE','1')
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'build/visuals'; OUT.mkdir(parents=True,exist_ok=True)
U=C.c_uint; I=C.c_int; F=C.c_float; P=C.c_void_p

def fn(lib,name,restype,*args):
 f=getattr(lib,name); f.restype=restype; f.argtypes=list(args); return f

class GL:
 def __init__(self,w=768,h=768):
  self.w=w; self.h=h
  self.egl=C.CDLL(ctypes.util.find_library('EGL') or 'libEGL.so.1')
  self.gl=C.CDLL(ctypes.util.find_library('GLESv2') or 'libGLESv2.so.2')
  self.display=fn(self.egl,'eglGetDisplay',P,P)(None)
  self.major=I(); self.minor=I()
  assert fn(self.egl,'eglInitialize',U,P,C.POINTER(I),C.POINTER(I))(self.display,C.byref(self.major),C.byref(self.minor)), 'EGL initialize failed'
  assert fn(self.egl,'eglBindAPI',U,U)(0x30A0)
  attrs=(I*15)(0x3033,1,0x3040,0x40,0x3024,8,0x3023,8,0x3022,8,0x3025,24,0x3038,0,0)
  config=P(); n=I()
  assert fn(self.egl,'eglChooseConfig',U,P,C.POINTER(I),C.POINTER(P),I,C.POINTER(I))(self.display,attrs,C.byref(config),1,C.byref(n)) and n.value
  self.surface=fn(self.egl,'eglCreatePbufferSurface',P,P,P,C.POINTER(I))(self.display,config,(I*5)(0x3057,w,0x3056,h,0x3038))
  self.context=fn(self.egl,'eglCreateContext',P,P,P,P,C.POINTER(I))(self.display,config,None,(I*3)(0x3098,3,0x3038))
  assert self.context and self.surface
  assert fn(self.egl,'eglMakeCurrent',U,P,P,P,P)(self.display,self.surface,self.surface,self.context)
  specs={
   'glCreateShader':(U,U),'glShaderSource':(None,U,I,C.POINTER(C.c_char_p),P),'glCompileShader':(None,U),
   'glGetShaderiv':(None,U,U,C.POINTER(I)),'glGetShaderInfoLog':(None,U,I,P,P),
   'glCreateProgram':(U,),'glAttachShader':(None,U,U),'glLinkProgram':(None,U),'glGetProgramiv':(None,U,U,C.POINTER(I)),
   'glGetProgramInfoLog':(None,U,I,P,P),'glUseProgram':(None,U),'glGetUniformLocation':(I,U,C.c_char_p),
   'glUniform1i':(None,I,I),'glUniform1f':(None,I,F),'glUniform2f':(None,I,F,F),
   'glUniform3f':(None,I,F,F,F),'glUniform4f':(None,I,F,F,F,F),'glUniformMatrix4fv':(None,I,I,U,P),
   'glGenVertexArrays':(None,I,C.POINTER(U)),'glBindVertexArray':(None,U),
   'glGenBuffers':(None,I,C.POINTER(U)),'glBindBuffer':(None,U,U),'glBufferData':(None,U,C.c_ssize_t,P,U),
   'glEnableVertexAttribArray':(None,U),'glVertexAttribPointer':(None,U,I,U,U,I,P),
   'glGenTextures':(None,I,C.POINTER(U)),'glActiveTexture':(None,U),'glBindTexture':(None,U,U),
   'glPixelStorei':(None,U,I),'glTexParameteri':(None,U,U,I),'glTexImage2D':(None,U,I,I,I,I,I,U,U,P),
   'glGenerateMipmap':(None,U),'glEnable':(None,U),'glDisable':(None,U),'glCullFace':(None,U),
   'glClearColor':(None,F,F,F,F),'glClear':(None,U),'glViewport':(None,I,I,I,I),
   'glDrawElements':(None,U,I,U,P),'glReadPixels':(None,I,I,I,I,U,U,P),'glGetError':(U,),
   'glGetString':(C.c_char_p,U),'glFinish':(None,),'glFrontFace':(None,U)
  }
  for name,spec in specs.items(): setattr(self,name,fn(self.gl,name,*spec))
  print('GL renderer:',self.glGetString(0x1F01).decode())
  self.keep=[]

 def program(self,vs,fs):
  stages=[]
  for typ,source in [(0x8B31,vs),(0x8B30,fs)]:
   shader=self.glCreateShader(typ); text=C.c_char_p(source.encode()); self.glShaderSource(shader,1,C.byref(text),None); self.glCompileShader(shader)
   ok=I(); self.glGetShaderiv(shader,0x8B81,C.byref(ok))
   if not ok.value:
    log=C.create_string_buffer(16384); self.glGetShaderInfoLog(shader,len(log),None,log); raise AssertionError(log.value.decode())
   stages.append(shader)
  prog=self.glCreateProgram()
  for s in stages: self.glAttachShader(prog,s)
  self.glLinkProgram(prog); ok=I(); self.glGetProgramiv(prog,0x8B82,C.byref(ok))
  if not ok.value:
   log=C.create_string_buffer(16384); self.glGetProgramInfoLog(prog,len(log),None,log); raise AssertionError(log.value.decode())
  self.glUseProgram(prog); self.prog=prog; return prog

 def uniform(self,name,*values):
  loc=self.glGetUniformLocation(self.prog,name.encode())
  if loc < 0: return
  if len(values)==1:
   val=values[0]
   if isinstance(val,int): self.glUniform1i(loc,val)
   elif isinstance(val,(list,tuple)):
    array=(F*16)(*val); self.glUniformMatrix4fv(loc,1,0,array)
   else: self.glUniform1f(loc,float(val))
  else: getattr(self,'glUniform'+str(len(values))+'f')(loc,*values)

 def mesh(self,vertices,indices):
  vao=U(); self.glGenVertexArrays(1,C.byref(vao)); self.glBindVertexArray(vao)
  v=(F*len(vertices))(*vertices); ix=(C.c_ushort*len(indices))(*indices)
  vb=U(); ib=U(); self.glGenBuffers(1,C.byref(vb)); self.glGenBuffers(1,C.byref(ib))
  self.glBindBuffer(0x8892,vb); self.glBufferData(0x8892,C.sizeof(v),v,0x88E4)
  self.glBindBuffer(0x8893,ib); self.glBufferData(0x8893,C.sizeof(ix),ix,0x88E4)
  for a,size,offset in [(0,3,0),(1,3,12),(2,2,24)]:
   self.glEnableVertexAttribArray(a); self.glVertexAttribPointer(a,size,0x1406,0,32,P(offset))
  self.count=len(indices); self.keep.extend([v,ix]); return vao.value

 def texture(self,path,slot,gray=False):
  fmt='gray' if gray else 'rgba'
  dims=subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','csv=p=0:s=x',str(path)],text=True).strip().split('x')
  w,h=map(int,dims)
  raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','rawvideo','-pix_fmt',fmt,'-'])
  pixels=C.create_string_buffer(raw); tex=U(); self.glGenTextures(1,C.byref(tex))
  self.glActiveTexture(0x84C0+slot); self.glBindTexture(0x0DE1,tex)
  self.glPixelStorei(0x0CF5,1)
  self.glTexImage2D(0x0DE1,0,0x8229 if gray else 0x1908,w,h,0,0x1903 if gray else 0x1908,0x1401,pixels)
  for prop,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x2901),(0x2803,0x812F)]: self.glTexParameteri(0x0DE1,prop,value)
  return tex.value

 def draw(self,cull=0x0405):
  self.glViewport(0,0,self.w,self.h); self.glClearColor(0.008,0.012,0.02,1.0); self.glClear(0x4000|0x0100)
  self.glEnable(0x0B71); self.glEnable(0x0B44); self.glCullFace(cull); self.glFrontFace(0x0901)
  self.glDrawElements(0x0004,self.count,0x1403,None); self.glFinish()
  error=self.glGetError(); assert error==0,hex(error)
  raw=C.create_string_buffer(self.w*self.h*4); self.glReadPixels(0,0,self.w,self.h,0x1908,0x1401,raw)
  rows=[raw.raw[y*self.w*4:(y+1)*self.w*4] for y in range(self.h)]
  return b''.join(reversed(rows))

 def close(self):
  fn(self.egl,'eglMakeCurrent',U,P,P,P,P)(self.display,None,None,None)
  fn(self.egl,'eglDestroyContext',U,P,P)(self.display,self.context)
  fn(self.egl,'eglDestroySurface',U,P,P)(self.display,self.surface)
  fn(self.egl,'eglTerminate',U,P)(self.display)

def png(path,w,h,pixels):
 def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 raw=b''.join(b'\0'+pixels[y*w*4:(y+1)*w*4] for y in range(h))
 path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))

def constants():
 src=ROOT/'app/src/main/java/com/example/render'
 defs={}
 for file in src.glob('*.kt'):
  for name,text in re.findall(r'(?:private\s+)?const val\s+(\w+)\s*=\s*"""(.*?)"""',file.read_text(),re.S): defs[name]=text
 for _ in range(30):
  new={name:re.sub(r'\$([A-Z][A-Z0-9_]*)',lambda m:defs.get(m[1],m[0]),text) for name,text in defs.items()}
  if new==defs: break
  defs=new
 return defs

def sub(a,b):return [x-y for x,y in zip(a,b)]
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def norm(a):return [x/math.sqrt(dot(a,a)) for x in a]
def cross(a,b):return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
def matmul(a,b):return [sum(a[k*4+r]*b[c*4+k] for k in range(4)) for c in range(4) for r in range(4)]
def view(eye):
 f=norm([-v for v in eye]); s=norm(cross(f,[0,1,0])); u=cross(s,f)
 return [s[0],u[0],-f[0],0,s[1],u[1],-f[1],0,s[2],u[2],-f[2],0,-dot(s,eye),-dot(u,eye),dot(f,eye),1]
def perspective(aspect=1,near=.01,far=200):
 f=1/math.tan(math.radians(25));return [f/aspect,0,0,0,0,f,0,0,0,0,(far+near)/(near-far),-1,0,0,2*far*near/(near-far),0]
IDENTITY=[1.,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]
def project(pos,mat,w,h):
 v=list(pos)+[1]; clip=[sum(mat[k*4+r]*v[k] for k in range(4)) for r in range(4)]
 return int((clip[0]/clip[3]*.5+.5)*w),int((.5-clip[1]/clip[3]*.5)*h)

def earth_position(lat,lon):
 a=6378.137;f=1/298.257223563;e2=f*(2-f);lat=math.radians(lat);lon=math.radians(lon);n=a/math.sqrt(1-e2*math.sin(lat)**2)
 return [(n*math.cos(lat)*math.cos(lon))/6371,(n*(1-e2)*math.sin(lat))/6371,-(n*math.cos(lat)*math.sin(lon))/6371]

def earth_render(defs):
 data=ROOT/'app/build/visual-input'
 raw=(data/'earth.vertices').read_bytes(); vertices=struct.unpack('>'+str(len(raw)//4)+'f',raw)
 raw=(data/'earth.indices').read_bytes(); indices=struct.unpack('>'+str(len(raw)//2)+'H',raw)
 assets=ROOT/'app/src/main/res/drawable-nodpi'
 reports=[]
 for iteration in range(2):
  gl=GL(); gl.mesh(vertices,indices)
  gl.texture(assets/'earth_blue_marble.jpg',1); gl.texture(assets/'earth_elevation.png',0,True); gl.texture(assets/'earth_night_lights.jpg',2,True)
  gl.program(defs['PLANET_VS'],defs['PLANET_FS'])
  values={'uModel':IDENTITY,'uLogDepthCoef':0.,'uBodyPos':(0.,0.,0.),'uBodyRadius':1.,'uUseObservedSurface':1,'uObservedSurfaceTex':1,'uEarthTex':0,'uEarthNightTex':2,'uBodyArchetype':5,'uPlanetStyle':0,'uTempK':288.,'uFbmOctaves':4,'uSeed':4.13,'uAlbedo':(.2,.48,.82),'uAtmoDensity':.65,'uAtmoColor':(.28,.62,1.),'uEnableAtmo':1,'uHasCityLights':1,'uHasClouds':0,'uStarCount':1,'uStarColor[0]':(1.,1.,1.),'uStarIntensity[0]':1.6,'uImpactShockAge':999.}
  for k,v in values.items(): gl.uniform(k,*(v if isinstance(v,tuple) else [v]))
  for name,lat,lon,marks in [('africa',15,20,[('sahara',22,20),('indian_ocean',0,65)]),('americas',10,-85,[('amazon',-5,-65),('pacific',0,-125)]),('asia',20,105,[('india',22,78)])]:
   eye=[v*3.4 for v in earth_position(lat,lon)]; vp=matmul(perspective(),view(eye))
   gl.uniform('uViewProj',vp); gl.uniform('uCamPos',*eye); gl.uniform('uStarPos[0]',*[v*3 for v in eye]); pixels=gl.draw()
   if iteration==0: png(OUT/f'earth-{name}.png',gl.w,gl.h,pixels)
   report={'view':name,'recreated_context':bool(iteration),'sha256':hashlib.sha256(pixels).hexdigest(),'landmarks':{}}
   for mark,mlat,mlon in marks:
    x,y=project(earth_position(mlat,mlon),vp,gl.w,gl.h); rgb=list(pixels[(y*gl.w+x)*4:(y*gl.w+x)*4+3]); report['landmarks'][mark]={'pixel':[x,y],'rgb':rgb}
    if mark in ('sahara','india'): assert rgb[0]>rgb[2]*1.15,(mark,rgb)
    elif mark in ('pacific','indian_ocean'): assert rgb[2]>rgb[0]*1.5,(mark,rgb)
    elif mark=='amazon': assert rgb[1]>rgb[2]*1.5,(mark,rgb)
   reports.append(report)
  gl.close()
 for a,b in zip(reports[:3],reports[3:]): assert a['sha256']==b['sha256'],'context recreation changed Earth output'
 return reports

if __name__=='__main__':
 defs=constants(); report={'earth':earth_render(defs)}
 (OUT/'visual-verification.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report,indent=2))
