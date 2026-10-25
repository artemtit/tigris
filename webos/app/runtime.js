(function(w){"use strict";
var zh=(navigator.language||"").toLowerCase().indexOf("zh")===0;
var words=zh?{settings:"设置",close:"关闭",welcome:"欢迎回家",headline:"让电视回归简单。",apps:"应用与输入源",weatherOn:"显示天气卡片（默认关闭）",city:"天气城市",fahrenheit:"使用华氏度 (°F)",search:"搜索",attribution:"天气来源：Open-Meteo。仅开启时发送所选城市坐标。",home:"Home 键",notEnabled:"未启用",rootCheck:"检查已有 Root 能力",quick:"当前预览版尚未提供 Home Helper。可使用 LG Quick Access 把 Tigris 绑定到数字键。"}:{settings:"Settings",close:"Close",welcome:"Welcome home",headline:"Your TV, beautifully simple.",apps:"Apps & Inputs",weatherOn:"Show weather card (off by default)",city:"Weather city",fahrenheit:"Use Fahrenheit (°F)",search:"Search",attribution:"Weather by Open-Meteo. Selected city coordinates are only sent when enabled.",home:"Home key",notEnabled:"Not enabled",rootCheck:"Check existing Root capabilities",quick:"The native Home Helper is not yet available in this preview. Use LG Quick Access to assign Tigris to a number key."};
function el(id){return document.getElementById(id)}function load(key,defaultValue){try{var v=JSON.parse(localStorage.getItem(key));return v===null?defaultValue:v}catch(_){return defaultValue}}function save(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(_){}}
Array.prototype.forEach.call(document.querySelectorAll("[data-t]"),function(e){e.textContent=words[e.getAttribute("data-t")]||""});
document.documentElement.lang=zh?"zh-CN":"en";
function clock(){var d=new Date();el("clock").textContent=("0"+d.getHours()).slice(-2)+":"+("0"+d.getMinutes()).slice(-2)}
clock();setInterval(clock,30000);
function open(){el("dialog").hidden=false;el("close").focus()}function close(){el("dialog").hidden=true;el("settings").focus()}
el("settings").onclick=open;el("close").onclick=close;el("weather").onclick=open;
var enabled=load("weather-enabled",false)===true,city=load("weather-city",null),fahrenheit=load("weather-fahrenheit",false)===true;el("fahrenheit").checked=fahrenheit;el("fahrenheit").onchange=function(){fahrenheit=this.checked;save("weather-fahrenheit",fahrenheit);showWeather()};el("weather-switch").checked=enabled;el("city").value=city?city.name:"";
function showWeather(){if(!enabled){el("weather").hidden=true;return}el("weather").hidden=false;
if(!city){el("weather-value").textContent=zh?"请选择城市":"Choose a city";el("weather-range").textContent="";return}
w.TigrisWeather.load(city).then(function(d){if(!enabled)return;el("weather-glyph").textContent=w.TigrisWeather.glyph(d.code);var format=function(c){return c===null?"—":fahrenheit?Math.round(c*9/5+32):c};var unit=fahrenheit?"°F":"°C";el("weather-value").textContent=d.name+"  "+format(d.temp)+unit;
el("weather-range").textContent=(d.high===null?"":"H "+format(d.high)+unit+"  ")+(d.low===null?"":"L "+format(d.low)+unit)}).catch(function(){el("weather-value").textContent=zh?"天气暂不可用":"Weather unavailable"})}
el("weather-switch").onchange=function(){enabled=this.checked;save("weather-enabled",enabled);showWeather()};
el("city-search").onclick=function(){var box=el("results");box.textContent="…";w.TigrisWeather.search(el("city").value).then(function(cities){box.textContent="";cities.forEach(function(c){var b=document.createElement("button");b.textContent=c.name+" · "+c.detail;b.onclick=function(){city={name:c.name,lat:c.lat,lon:c.lon};save("weather-city",city);el("city").value=c.name;box.textContent="✓ "+c.name;showWeather()};box.appendChild(b)});if(!cities.length)box.textContent=zh?"找不到城市":"No cities found"}).catch(function(){box.textContent="Search unavailable"})};
el("root-check").onclick=function(){w.TigrisRoot.inspect().then(function(r){
  el("home-status").textContent=r.available?(zh?"已检测到 Root；Home 服务尚未安装":"Root detected; Home service not installed"):(zh?"未检测到 Root 权限":"Root not detected");
  if(r.available){w.TigrisRoot.listApps().then(function(a){if(a.length)draw(a)},function(){});}
})};
function draw(apps){var dock=el("dock");dock.textContent="";apps.slice(0,8).forEach(function(a){var b=document.createElement("button");b.className="tile";b.title=a.title;
if(/^file:\/\//.test(a.icon||"")){var img=document.createElement("img");img.src=a.icon;img.alt="";img.onerror=function(){img.style.display="none"};b.appendChild(img)}
var label=document.createElement("small");label.textContent=a.title;b.appendChild(label);b.onclick=function(){el("apps-message").textContent=zh?"正在启动…":"Launching…";w.TigrisCore.launch(a.id).then(function(){el("apps-message").textContent=""},function(){el("apps-message").textContent=zh?"启动失败":"Launch failed"})};dock.appendChild(b)});if(!dock.children.length)el("apps-message").textContent=zh?"暂时无法读取应用列表":"App list unavailable";else dock.children[0].focus()}
el("status").textContent=zh?"LG webOS 开发预览版 · 原厂 Home 不受影响":"LG webOS developer preview · Stock Home unchanged";
el("apps-message").textContent="…";
Promise.all([w.TigrisCore.listApps().catch(function(){return[]}),w.TigrisCore.listInputs()]).then(function(v){draw(v[0].concat(v[1]))});
document.addEventListener("keydown",function(e){if(e.keyCode===461||e.keyCode===27||e.key==="Escape"){if(!el("dialog").hidden){e.preventDefault();close()}return}
if((e.keyCode===37||e.keyCode===39)&&el("dialog").hidden){var b=Array.prototype.slice.call(document.querySelectorAll(".tile")),i=b.indexOf(document.activeElement);
if(i>=0){e.preventDefault();b[(i+(e.keyCode===39?1:b.length-1))%b.length].focus()}}});
showWeather();setInterval(function(){if(enabled&&!document.hidden)showWeather()},1800000);
})(window);
