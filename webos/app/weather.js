/* No lookup, network request, or location access happens unless enabled by user. */
(function(g){"use strict";var NAME="tigris-webos-weather",FRESH=1800000,MAX=21600000;
function valid(c){return!!c&&isFinite(c.lat)&&isFinite(c.lon)&&Math.abs(c.lat)<=90&&Math.abs(c.lon)<=180}
function get(url){return new Promise(function(ok,fail){var x=new XMLHttpRequest();x.open("GET",url,true);x.timeout=12000;
x.onload=function(){if(x.status!==200)return fail(Error("Weather unavailable"));try{ok(JSON.parse(x.responseText))}catch(e){fail(e)}};
x.onerror=x.ontimeout=function(){fail(Error("Weather connection failed"))};x.send()})}
function search(name){if(String(name||"").trim().length<2)return Promise.resolve([]);
return get("https://geocoding-api.open-meteo.com/v1/search?name="+encodeURIComponent(name.trim().slice(0,60))+"&count=8&language="+((navigator.language||"").toLowerCase().indexOf("zh")===0?"zh":"en")+"&format=json").then(function(j){
return(j.results||[]).map(function(c){return{name:c.name,detail:[c.admin1,c.country].filter(Boolean).join(", "),lat:c.latitude,lon:c.longitude}}).filter(valid)})}
function cached(c,age){try{var d=JSON.parse(localStorage.getItem(NAME)||"null");return d&&d.lat===c.lat&&d.lon===c.lon&&Date.now()>=d.at&&Date.now()-d.at<age?d:null}catch(_){return null}}
function parse(j,c,at){if(!j||!j.current||!valid(c))return null;var t=Number(j.current.temperature_2m),code=Number(j.current.weather_code);
if(!isFinite(t)||!isFinite(code))return null;var d=j.daily||{};
return{name:c.name,lat:c.lat,lon:c.lon,at:at,temp:Math.round(t),code:code,high:d.temperature_2m_max?Math.round(d.temperature_2m_max[0]):null,low:d.temperature_2m_min?Math.round(d.temperature_2m_min[0]):null}}
function load(c){if(!valid(c))return Promise.reject(Error("Select city"));var old=cached(c,FRESH);if(old)return Promise.resolve(old);
var url="https://api.open-meteo.com/v1/forecast?latitude="+c.lat+"&longitude="+c.lon+"&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
return get(url).then(function(j){var d=parse(j,c,Date.now());if(!d)throw Error("Invalid weather reply");try{localStorage.setItem(NAME,JSON.stringify(d))}catch(_){}return d}).catch(function(e){var last=cached(c,MAX);if(last)return last;throw e})}
function glyph(code){if(code===0)return"☀";if(code<=3)return"⛅";if(code<=48)return"☁";if(code<=67||code>=80&&code<=82)return"☂";if(code<=77||code===85||code===86)return"❄";if(code>=95)return"⚡";return"☁"}
g.TigrisWeather={search:search,load:load,parse:parse,valid:valid,glyph:glyph,FRESH:FRESH,MAX:MAX};
})(window);
