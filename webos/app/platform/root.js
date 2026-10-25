/* Advanced edition: authorized READ-ONLY inspection of already-existing Root access.
   Does not root, flash, install any service, or intercept Home. */
(function(g){"use strict";g.TigrisRoot={
  id:"existing-root",
  inspect:function(){return g.TigrisCore.call("luna://org.webosbrew.hbchannel.service/exec",{command:"id -u"},6000).then(function(r){
    return{available:String(r.stdoutString||r.stdout||"").trim()==="0",configured:false};
  }).catch(function(){return{available:false,configured:false}})},
  /** User-invoked, strictly read-only full inventory; no arbitrary shell input. */
  listApps:function(){
    var command="luna-send -n 1 luna://com.webos.applicationManager/listLaunchPoints '{}'";
    return g.TigrisCore.call("luna://org.webosbrew.hbchannel.service/exec",{command:command},9000)
      .then(function(reply){
        var raw=String(reply.stdoutString||reply.stdout||"");
        if(raw.length>262144)throw Error("Inventory too large");
        var parsed=JSON.parse(raw);
        return(parsed.launchPoints||[]).filter(function(a){
          return a&&a.id&&a.title&&a.id!=="org.tigris.webos";
        }).map(function(a){return{id:a.id,title:a.title,icon:""};}).slice(0,64);
      });
  }
}})(window);
