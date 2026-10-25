(function(g){"use strict";
function call(uri,args,timeout){return new Promise(function(ok,fail){
  if(typeof g.PalmServiceBridge!=="function")return fail(Error("webOS Luna not available"));
  var bridge=new g.PalmServiceBridge(),done=false;
  var timer=setTimeout(function(){if(!done){done=true;fail(Error("Luna timeout"));}},timeout||6500);
  bridge.onservicecallback=function(reply){if(done)return;var data;
    try{data=JSON.parse(reply)}catch(e){done=true;clearTimeout(timer);return fail(e)}
    if(data&&data.subscribed)return;done=true;clearTimeout(timer);
    if(data&&data.returnValue===false)return fail(Error(data.errorText||"Service denied"));ok(data);
  };
  try{bridge.call(uri,JSON.stringify(args||{}))}catch(e){done=true;clearTimeout(timer);fail(e)}
})}
function launch(app){if(!/^[a-z0-9_.-]{3,160}$/i.test(app||""))return Promise.reject(Error("Invalid app ID"));
return call("luna://com.webos.applicationManager/launch",{id:app},7000)}
/* Ordinary webOS web apps may be denied listLaunchPoints.
   getAppLoadStatus is public; only present known IDs actually installed. */
var CATALOG=[
  {id:"youtube.leanback.v4",title:"YouTube"},{id:"netflix",title:"Netflix"},
  {id:"amazon",title:"Prime Video"},{id:"com.disney.disneyplus-prod",title:"Disney+"},
  {id:"com.apple.appletv",title:"Apple TV"},{id:"cdp-30",title:"Plex"},
  {id:"spotify-beehive",title:"Spotify"},{id:"com.webos.app.browser",title:"Browser"},
  {id:"com.webos.app.mediadiscovery",title:"Media Player"},
  {id:"com.webos.app.home",title:"LG Home"},
  {id:"com.palm.app.settings",title:"Settings"},
  {id:"com.webos.app.settings",title:"Settings"},
  {id:"org.webosbrew.hbchannel",title:"Homebrew"}
];
function catalog(){return Promise.all(CATALOG.map(function(a){
  return call("luna://com.webos.applicationManager/getAppLoadStatus",{appId:a.id},4500)
    .then(function(r){return r.exist===true?a:null},function(){return null})
})).then(function(rows){return rows.filter(Boolean)})}
function listApps(){return call("luna://com.webos.applicationManager/listLaunchPoints",{},7000).then(function(d){
  var list=(d.launchPoints||[]).filter(function(a){return a&&a.id&&a.title&&a.id!=="org.tigris.webos"})
    .map(function(a){return{id:a.id,title:a.title,icon:a.icon||a.largeIcon||""}});
  return list.length?list:catalog();
},catalog)}

function listInputs(){return call("luna://com.webos.service.eim/getAllInputStatus",{},5000).then(function(r){
return(r.devices||[]).filter(function(d){return d.appId&&d.label}).map(function(d){return{id:d.appId,title:d.label,icon:""}})}).catch(function(){return[]})}
g.TigrisCore={call:call,launch:launch,listApps:listApps,listInputs:listInputs};
})(window);
