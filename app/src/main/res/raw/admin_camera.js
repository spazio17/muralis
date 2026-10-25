// An RTSP camera's picture in its form: a browser cannot play RTSP, so the panel plays it and
// sends one picture when Show a picture (or Take a new picture) is pressed, with the time it
// came. Nothing is asked until then; a refusal says why under the button.
(function(){
document.addEventListener('click',function(e){
var b=e.target.closest('[data-camera-picture]');if(!b){return;}
var box=b.closest('.camera-picture'),img=box.querySelector('img'),hint=box.querySelector('p'),
taken=box.querySelector('[data-taken]');
hint.textContent='Asking the camera...';hint.hidden=false;b.disabled=true;
fetch('/api/camera/picture?name='+encodeURIComponent(b.getAttribute('data-camera-picture'))
+'&t='+Date.now(),{credentials:'same-origin',cache:'no-store'})
.then(function(r){
if(r.ok){return r.blob().then(function(blob){
if(img.src&&img.src.indexOf('blob:')===0){URL.revokeObjectURL(img.src);}
img.src=URL.createObjectURL(blob);img.hidden=false;hint.hidden=true;
var d=new Date();taken.textContent='Taken '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
b.textContent='Take a new picture';});}
return r.text().then(function(text){hint.textContent=text||'No picture: the camera did not answer';img.hidden=true;});})
.catch(function(){hint.textContent='No picture: the panel did not answer';img.hidden=true;})
.then(function(){b.disabled=false;});});
})();
