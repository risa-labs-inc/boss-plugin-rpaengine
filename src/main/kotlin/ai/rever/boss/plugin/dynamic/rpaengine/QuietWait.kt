package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.api.BrowserIntegration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The quiet wait: what rpa_step action verbs do after acting, instead of a fixed 300 ms sleep.
 * One page script waits two animation frames, then until the DOM has had no mutations for QUIET_MS (capped at
 * QUIET_CAP_MS), stopping early when location changes. executeJavaScript cannot await a promise through the
 * plugin API, so the script sets window.__rpaQuiet.done and Kotlin polls it every QUIET_POLL_MS.
 */

internal const val QUIET_MS = 100L
internal const val QUIET_CAP_MS = 800L
internal const val QUIET_POLL_MS = 30L

/** Kotlin gives up this long after the page's own cap: timers and frames are throttled in a hidden page. */
internal const val QUIET_GRACE_MS = 150L

/** One script call must answer within this; a page blocked in alert() would otherwise hold the step. */
internal const val QUIET_CALL_TIMEOUT_MS = 400L

/**
 * Starts the observer and returns true at once. Mutations of this plugin's own overlay nodes (the gliding pointer,
 * the ripple, the highlight) do not count. A page whose frames never fire (hidden tab) is treated as framed after 250 ms.
 */
internal fun quietStartScript(quietMs: Long = QUIET_MS, capMs: Long = QUIET_CAP_MS): String =
    """(function(){/*rpa-quiet*/try{
var Q=$quietMs,CAP=$capMs,d=document,q=window.__rpaQuiet={done:false},href=location.href,t0=Date.now(),last=t0,mo=null,frames=0;
function el(n){return n&&(n.nodeType===1?n:n.parentElement);}
function ours(n){try{var e=el(n);return !!(e&&e.closest&&e.closest('[data-rpa-pointer],[data-rpa-highlight]'));}catch(x){return false;}}
function fin(){if(q.done)return;q.done=true;try{if(mo)mo.disconnect();}catch(x){}}
if(window.MutationObserver){
mo=new MutationObserver(function(rs){for(var i=0;i<rs.length;i++){var r=rs[i];
if(ours(r.target))continue;
if(r.type==='childList'&&(r.addedNodes.length||r.removedNodes.length)){var all=true,k;
for(k=0;k<r.addedNodes.length;k++){if(!ours(r.addedNodes[k]))all=false;}
for(k=0;k<r.removedNodes.length;k++){if(!ours(r.removedNodes[k]))all=false;}
if(all)continue;}
last=Date.now();return;}});
mo.observe(d.documentElement,{childList:true,subtree:true,attributes:true,characterData:true});}
function tick(){if(q.done)return;if(location.href!==href){fin();return;}
var n=Date.now();if(n-t0>=CAP||(frames>=2&&n-last>=Q)){fin();return;}setTimeout(tick,20);}
function raf(f){if(window.requestAnimationFrame){window.requestAnimationFrame(f);}else{setTimeout(f,16);}}
raf(function(){frames=1;raf(function(){frames=2;tick();});});
setTimeout(function(){if(frames<2){frames=2;}tick();},250);
setTimeout(fin,CAP+50);
return true;}catch(e){return true;}})()"""

/** True when the quiet wait is over, or when its flag is gone (the page navigated away). */
internal fun quietPollScript(): String = "(function(){var q=window.__rpaQuiet;return !q||q.done===true;})()"

/**
 * Runs the quiet wait. Returns early when a browser dialog is pending (a script would block behind it) or when a
 * call fails or times out (the page is navigating or blocked). Never throws except for cancellation.
 */
internal suspend fun awaitQuiet(
    browser: BrowserIntegration,
    hasDialog: () -> Boolean = { false },
    quietMs: Long = QUIET_MS,
    capMs: Long = QUIET_CAP_MS,
) {
    if (hasDialog()) return
    val deadline = System.currentTimeMillis() + capMs + QUIET_GRACE_MS
    val started = orNullUnlessCancelled { withTimeoutOrNull(QUIET_CALL_TIMEOUT_MS) { browser.executeJavaScript(quietStartScript(quietMs, capMs)) } }
    if (started == null) return
    while (System.currentTimeMillis() < deadline) {
        delay(QUIET_POLL_MS)
        if (hasDialog()) return
        val done = orNullUnlessCancelled { withTimeoutOrNull(QUIET_CALL_TIMEOUT_MS) { browser.executeJavaScript(quietPollScript()) } }
        if (done == null || done.isJsTrue()) return
    }
}
