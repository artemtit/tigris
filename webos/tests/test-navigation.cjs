const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
// Minimal DOM for real runtime handlers; no network, TV services or weather requests.
const elements = {};
const document = {activeElement:null, hidden:false, documentElement:{},
  getElementById:id=>elements[id],
  createElement:()=>element(),
  querySelectorAll:selector=>selector === '.tile' ? elements.dock.children : [],
  addEventListener:(name,fn)=>{ if(name==='keydown') document.keydown=fn; }};
function element(id='') {
  let text='';
  return {id, children:[], hidden:false, disabled:false, checked:false,
    get textContent(){return text;}, set textContent(v){text=v;this.children=[];},
    focus(){document.activeElement=this;}, scrollIntoView(){}, getClientRects(){return [1];},
    appendChild(child){this.children.push(child);}, style:{}};
}
for(const id of ['clock','dialog','close','settings','weather','fahrenheit','weather-switch',
  'city','weather-value','weather-range','weather-glyph','city-search','results',
  'root-check','home-status','dock','status','apps-message'])elements[id]=element(id);
elements.dialog.hidden=true;
elements.dialog.querySelectorAll=()=>['close','weather-switch','fahrenheit','city','city-search','root-check'].map(id=>elements[id]);
const apps=Array.from({length:12},(_,i)=>({id:'app.'+i,title:'App '+i}));
const window={TigrisCore:{listApps:()=>Promise.resolve(apps),listInputs:()=>Promise.resolve([{id:'hdmi.1',title:'HDMI 1'}])},
  TigrisRoot:{inspect:()=>Promise.resolve({available:true}),listApps:()=>Promise.resolve(apps)},
  TigrisWeather:{load(){throw Error('Weather must stay off');}}};
const context={window,document,navigator:{language:'en'},localStorage:{getItem:()=>null,setItem(){}},setInterval(){},Date,Promise,JSON};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/runtime.js'),'utf8'),context);
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const key=keyCode=>document.keydown({keyCode,preventDefault(){}});
(async()=>{
  await settle();
  assert.equal(elements.dock.children.length,13,'do not truncate apps or HDMI');
  key(38); assert.equal(document.activeElement.id,'settings');
  elements.settings.onclick(); assert.equal(document.activeElement.id,'close');
  key(40); assert.equal(document.activeElement.id,'weather-switch');
  key(40); assert.equal(document.activeElement.id,'fahrenheit');
  key(40); assert.equal(document.activeElement.id,'city');
  key(39); assert.equal(document.activeElement.id,'city','text input retains left/right editing');
  key(27); assert.equal(document.activeElement.id,'settings');
  key(40);
  for(let i=0;i<12;i++)key(39);
  assert.equal(document.activeElement.title,'HDMI 1');
  elements.settings.onclick();elements['root-check'].focus();elements['root-check'].onclick();
  await settle();
  assert.equal(document.activeElement.id,'root-check','async inventory must not steal dialog focus');
  assert.equal(elements.dock.children.length,13,'Root refresh retains HDMI');
  console.log('webOS runtime navigation, complete inventory, dialog and Root refresh PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
