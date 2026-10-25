/* Standard edition. Does NOT take exclusive control of remote inputs. */
(function(g){"use strict";g.TigrisNoRoot={
  id:"no-root",status:function(){return{installed:true,helper:"not-implemented",verified:false}},
  preflight:function(){return Promise.resolve({state:"candidate_unverified",reason:"No watchdog-protected native Home Helper has been published for Tigris."})},
  quickAccess:function(){return"Use LG Quick Access to bind Tigris to a number key on your remote."}
}})(window);
