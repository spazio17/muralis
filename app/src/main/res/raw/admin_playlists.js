// The playlist table, without the page reloading under the reader.
//
// Use, Delete and Create playlist used to post for real and come back as the whole screensaver
// page with a sentence on top ("Playlist in use.") and /api/playlists/activate in the address bar.
// For Use the sentence said nothing that the "In use" mark moving to the row did not already say
// (Juri, 2026-09-19: tap the playlist you want and simply switch). So the table is re-read in place
// and a success is silent; only a refusal is said, in the banner under the table.
//
// One delegated listener on the box rather than one per row: the box's contents are replaced on
// every change, and per-row listeners would have to be re-attached each time.
(function(){
var box=document.getElementById('playlist-table');
if(!box){return;}
var banner=document.getElementById('playlist-banner'),hideTimer=0;

function say(message){
if(!banner){return;}
if(hideTimer){clearTimeout(hideTimer);}
// An empty sentence is "nothing to say": the table re-read below is the whole answer.
if(!message){banner.classList.add('gone');return;}
banner.firstChild.textContent=message;
banner.classList.remove('gone');
// Five seconds, and a cross for anyone who wants it gone sooner.
hideTimer=setTimeout(function(){banner.classList.add('gone');},5000);
}
if(banner){banner.querySelector('.dismiss').addEventListener('click',function(){say('');});}

var unreachable='The panel did not answer. It may be rebooting or off the network.';

function reload(){
return fetch('/api/playlists/table',{credentials:'same-origin'})
.then(function(r){return r.text();})
.then(function(html){box.innerHTML=html;})
.catch(function(){say(unreachable);});
}

// The radio in front of a row is Use: picking it posts the form it sits in, at once.
box.addEventListener('change',function(event){
var radio=event.target;
if(!radio||radio.type!=='radio'||radio.name!=='active'){return;}
var form=radio.closest?radio.closest('form.use'):null;
if(form){form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));}});

// Edit is a link to the playlist's own page; it is left alone.
box.addEventListener('submit',function(event){
var form=event.target;
if(form.tagName!=='FORM'||(form.getAttribute('method')||'get').toLowerCase()!=='post'){return;}
event.preventDefault();
// Delete asks first, as the panel does: a playlist is gone for good, and Delete sits next to
// Edit on a phone (review of 2026-09-19).
if(/\/api\/playlists\/delete$/.test(form.action)){
var row=form.closest('li'),name=row&&row.querySelector('.h')?row.querySelector('.h').textContent:'this playlist';
if(!window.confirm('Delete '+name+'? The pictures themselves are not deleted.')){return;}}
// URL-encoded, not FormData: a FormData body is sent as multipart, and the server parses
// multipart for the upload alone.
var data=new URLSearchParams(new FormData(form));
fetch(form.action+'?fragment=1',{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded'},body:data.toString()})
.then(function(r){return r.text();})
.then(function(text){var ok=false,message='';
try{var parsed=JSON.parse(text);ok=parsed.ok!==false;message=parsed.message||'';}
catch(ignored){message=text;}
say(ok?'':(message||'The panel refused the change.'));
return reload();})
.catch(function(){say(unreachable);});
});
})();
