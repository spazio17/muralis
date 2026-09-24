// The picture browser, without the page reloading under the reader.
//
// Every folder tap, tick, name and delete used to be a form post that re-rendered the whole
// settings page, so the browser threw the document away and came back at the top: pick a folder,
// scroll down, tick one picture, and you are at the top again (Juri, 2026-09-10, C3 and C4). The
// server answers all of it twice over now, as a page for a browser with no scripting and as a
// fragment plus one sentence for this file, so with scripting nothing navigates at all.
//
// One delegated listener on the container rather than a listener per row: the container's contents
// are replaced on every change, and per-row listeners would have to be re-attached each time.
(function(){
var box=document.getElementById('picture-browser');
if(!box){return;}
var banner=document.getElementById('picture-banner'),
    upload=document.getElementById('picture-upload'),
    at='',hideTimer=0;
// The folder the browser is showing, taken from the address so a reload, a bookmark or a
// second tab opens the same folder. It is never stored on the panel: two people browsing from
// two machines must not move each other's view.
var startAt=/[?&]at=([^&]*)/.exec(window.location.search);
if(startAt){at=decodeURIComponent(startAt[1].replace(/\+/g,' '));}

function say(message,ok){
if(!banner){return;}
banner.firstChild.textContent=message;
banner.classList.toggle('bad',!ok);
banner.classList.remove('gone');
// Five seconds, and a cross for anyone who wants it gone sooner. A banner that stays until the
// next action is a banner that is still claiming something after it stopped being true.
if(hideTimer){clearTimeout(hideTimer);}
hideTimer=setTimeout(function(){banner.classList.add('gone');},5000);
}
if(banner){banner.querySelector('.dismiss').addEventListener('click',function(){
banner.classList.add('gone');
if(hideTimer){clearTimeout(hideTimer);}});}

// Re-reads the browser at the folder it is showing. The address bar follows, without a history
// entry per folder: going back should leave the page, not walk back through forty folders.
function reload(){
return fetch('/api/pictures/browse?at='+encodeURIComponent(at),{credentials:'same-origin'})
.then(function(r){return r.text();})
.then(function(html){box.innerHTML=html;
try{history.replaceState(null,'',at?'/screensaver?at='+encodeURIComponent(at):'/screensaver');}
catch(ignored){}})
.catch(function(){say('The panel did not answer. It may be rebooting or off the network.',false);});
}

function openFolder(target){at=target;reload();}

box.addEventListener('click',function(event){
var link=event.target.closest?event.target.closest('a.folder,a.pager'):null;
if(!link){return;}
event.preventDefault();
openFolder(link.dataset.at||'');
});

// Every form inside the browser posts the same way and is answered the same way, so one handler
// covers ticking a picture, naming it, deleting an upload and all three playlist actions.
box.addEventListener('submit',function(event){
var form=event.target;
if(form.tagName!=='FORM'){return;}
event.preventDefault();
// URL-encoded, not FormData: a FormData body is sent as multipart, and the server parses
// multipart for the upload alone. Posted as multipart these fields arrived empty, so ticking a
// picture was refused with "Supply a boolean selection" (caught in the browser, 2026-09-10).
var data=new URLSearchParams(new FormData(form));
data.set('at',at);
fetch(form.action+'?fragment=1',{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded'},body:data.toString()})
.then(function(r){return r.text();})
.then(function(text){var ok=true,message=text;
try{var parsed=JSON.parse(text);ok=parsed.ok!==false;message=parsed.message||text;}
catch(ignored){ok=false;}
say(message,ok);
return reload();})
.catch(function(){say('The panel did not answer. It may be rebooting or off the network.',false);});
});

// The upload is the same shape, but it carries files, so it keeps the form's own encoding and
// only the answer is handled here.
if(upload){upload.addEventListener('submit',function(event){
event.preventDefault();
var files=upload.querySelector('input[type=file]');
if(files&&files.files&&files.files.length===0){say('Choose a picture first.',false);return;}
say('Uploading...',true);
fetch('/api/pictures?fragment=1',{method:'POST',credentials:'same-origin',
body:new FormData(upload)})
.then(function(r){return r.text();})
.then(function(text){var ok=true,message=text;
try{var parsed=JSON.parse(text);ok=parsed.ok!==false;message=parsed.message||text;}
catch(ignored){ok=false;}
say(message,ok);
if(files){files.value='';}
return reload();})
.catch(function(){say('The upload did not finish. Please try again.',false);});});}
})();
