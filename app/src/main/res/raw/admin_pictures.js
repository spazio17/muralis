// The playlist page, without the page reloading under the reader.
//
// Every folder tap, tick, name and delete used to be a form post that re-rendered the whole
// settings page, so the browser threw the document away and came back at the top: pick a folder,
// scroll down, tick one picture, and you are at the top again (Juri, 2026-09-10, C3 and C4). The
// server answers all of it twice over now, as a page for a browser with no scripting and as
// fragments plus one sentence for this file, so with scripting nothing navigates at all. Three
// fragments since 2026-09-19: the Folders panel, the Content panel and what the playlist holds.
//
// Delegated listeners on <main> rather than a listener per row: the panels' contents are replaced
// on every change, and per-row listeners would have to be re-attached each time.
(function(){
var folders=document.getElementById('folder-list'),content=document.getElementById('folder-content');
if(!folders||!content){return;}
var main=document.querySelector('main')||document;
var banner=document.getElementById('picture-banner'),
    upload=document.getElementById('picture-upload'),
    picked=document.getElementById('playlist-items'),
    at=null,hideTimer=0;
// The folder the browser is showing, taken from the address so a reload, a bookmark or a
// second tab opens the same folder. It is never stored on the panel: two people browsing from
// two machines must not move each other's view. null is "none opened yet", which is not the
// same as "" (the top of the volume); the right pane says so.
var startAt=/[?&]at=([^&]*)/.exec(window.location.search);
if(startAt){at=decodeURIComponent(startAt[1].replace(/\+/g,' '));}
// Which playlist this page is editing. The browser belongs to one playlist since 2026-09-12, so
// every fetch and every form carries it; without it the panel would edit whichever is playing.
var playlist='';
var startId=/[?&]id=([^&]*)/.exec(window.location.search);
if(startId){playlist=decodeURIComponent(startId[1].replace(/\+/g,' '));}
// How many pictures Content shows at once: ten unless the address says otherwise, the same four
// choices the panel offers. It rides in the address so a reload keeps it, and in every fragment
// request so the panel pages the same way.
var n=10;
var startN=/[?&]n=(\d+)/.exec(window.location.search);
if(startN){n=parseInt(startN[1],10);}

// A success says nothing on this page (Juri, 2026-09-19: "Playlist updated." on every added
// picture was noise): the panels re-read after it are the whole answer. Only a refusal is said,
// red, and "Uploading..." while a file is on its way, which is a state and not a verdict.
function say(message,ok){
if(!banner){return;}
if(ok===true||!message){banner.classList.add('gone');if(hideTimer){clearTimeout(hideTimer);}return;}
banner.firstChild.textContent=message;
banner.classList.toggle('bad',ok===false);
banner.classList.remove('gone');
// Five seconds, and a cross for anyone who wants it gone sooner. A banner that stays until the
// next action is a banner that is still claiming something after it stopped being true.
if(hideTimer){clearTimeout(hideTimer);}
hideTimer=setTimeout(function(){banner.classList.add('gone');},5000);
}
if(banner){banner.querySelector('.dismiss').addEventListener('click',function(){
banner.classList.add('gone');
if(hideTimer){clearTimeout(hideTimer);}});}

// Re-reads the three panels at the folder that is open. The address bar follows, without a
// history entry per folder: going back should leave the page, not walk back through forty folders.
function reload(){
var q='?playlist='+encodeURIComponent(playlist)
       +(at===null?'':'&at='+encodeURIComponent(at))+'&n='+n;
var shown='/playlist?id='+encodeURIComponent(playlist)
       +(at===null?'':'&at='+encodeURIComponent(at))+'&n='+n;
function text(r){return r.text();}
// All three, because a tick changes the folder's row, an upload changes a folder's count, and
// both change the list of what is held; a page whose panels disagree is a page nobody trusts.
return Promise.all([
fetch('/api/pictures/folders'+q,{credentials:'same-origin'}).then(text),
fetch('/api/pictures/content'+q,{credentials:'same-origin'}).then(text),
picked?fetch('/api/playlists/items?playlist='+encodeURIComponent(playlist),
{credentials:'same-origin'}).then(text):null])
.then(function(parts){folders.innerHTML=parts[0];content.innerHTML=parts[1];
if(picked&&parts[2]!==null){picked.innerHTML=parts[2];}
try{history.replaceState(null,'',shown);}
catch(ignored){}})
.catch(function(){say('The panel did not answer. It may be rebooting or off the network.',false);});
}

function openFolder(target){at=target;reload();}

// The title is the playlist's name and the pencil beside it opens the rename box. Opening it
// puts the cursor in the name; Cancel closes it; a refusal is said inside the box, under the name,
// and a success needs no sentence, the title itself has changed.
var rename=document.querySelector('details.rename');
if(rename){rename.addEventListener('toggle',function(){
if(rename.open){var field=rename.querySelector('input[name=name]');field.focus();field.select();}});
main.addEventListener('click',function(event){
if(event.target.closest&&event.target.closest('details.rename .cancel')){rename.open=false;}});}
function renamed(form,ok,message){
var problem=form.querySelector('.hint');
if(ok){document.getElementById('playlist-name').textContent=form.elements.name.value.trim();
problem.classList.add('gone');rename.open=false;return;}
problem.textContent=message||'The panel refused the name.';problem.classList.remove('gone');
}

main.addEventListener('click',function(event){
var link=event.target.closest?event.target.closest('a.folder,a.pager,a.size'):null;
if(!link){return;}
event.preventDefault();
if(link.classList.contains('size')){
// A new size starts the folder over: page three of ten is nowhere in particular at fifty.
n=parseInt(link.dataset.n,10);
if(at!==null&&at.indexOf('|')>=0){at=at.slice(at.indexOf('|')+1);}
reload();return;}
openFolder(link.dataset.at||'');
});

// Every form on this page posts the same way and is answered the same way, so one handler covers
// ticking a picture, naming it, removing it, deleting an upload and renaming the playlist. It is
// on <main> rather than on the browser because the name box and the held-pictures list are
// outside the browser and would otherwise navigate to /api/... and leave that address in the bar.
main.addEventListener('submit',function(event){
var form=event.target;
if(form.tagName!=='FORM'||form.id==='picture-upload'){return;}
// A GET form is navigation, not a change: Edit opens the playlist's page and must be left alone.
if((form.getAttribute('method')||'get').toLowerCase()!=='post'){return;}
event.preventDefault();
// URL-encoded, not FormData: a FormData body is sent as multipart, and the server parses
// multipart for the upload alone. Posted as multipart these fields arrived empty, so ticking a
// picture was refused with "Supply a boolean selection" (caught in the browser, 2026-09-10).
var data=new URLSearchParams(new FormData(form));
if(at===null){data.delete('at');}else{data.set('at',at);}
data.set('playlist',playlist);
fetch(form.action+'?fragment=1',{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded'},body:data.toString()})
.then(function(r){return r.text();})
.then(function(text){var ok=true,message=text;
try{var parsed=JSON.parse(text);ok=parsed.ok!==false;message=parsed.message||'';}
catch(ignored){ok=false;}
if(form.closest&&form.closest('details.rename')){renamed(form,ok,message);return;}
say(message,ok);
return reload();})
.catch(function(){say('The panel did not answer. It may be rebooting or off the network.',false);});
});

// The file input is off screen and Browse is its label, so the page has to say what was chosen.
var chooser=document.getElementById('picture-file'),chosen=document.getElementById('picture-chosen');
function sayChosen(){if(!chooser||!chosen){return;}
var n=chooser.files?chooser.files.length:0;
chosen.textContent=n===0?'No file chosen':n===1?chooser.files[0].name:n+' files chosen';}
if(chooser){chooser.addEventListener('change',sayChosen);}

// The upload is the same shape, but it carries files, so it keeps the form's own encoding and
// only the answer is handled here.
if(upload){upload.addEventListener('submit',function(event){
event.preventDefault();
var files=upload.querySelector('input[type=file]');
if(files&&files.files&&files.files.length===0){say('Choose a picture first.',false);return;}
say('Uploading...','busy');
fetch('/api/pictures?fragment=1&playlist='+encodeURIComponent(playlist),
{method:'POST',credentials:'same-origin',body:new FormData(upload)})
.then(function(r){return r.text();})
.then(function(text){var ok=true,message=text;
try{var parsed=JSON.parse(text);ok=parsed.ok!==false;message=parsed.message||'';}
catch(ignored){ok=false;}
say(message,ok);
if(files){files.value='';sayChosen();}
return reload();})
.catch(function(){say('The upload did not finish. Please try again.',false);});});}
})();
