//  Light / dark / follow-the-device, remembered in the browser rather than on the tablet. */
(function(){
var KEY='kiosk-admin-theme';
function apply(mode){
document.documentElement.setAttribute('data-theme',mode);
Array.prototype.forEach.call(document.querySelectorAll('.themepick button'),
function(b){b.setAttribute('aria-pressed',String(b.dataset.theme===mode));});}
var saved=null;
try{saved=localStorage.getItem(KEY);}catch(e){}
apply(saved||'system');
document.addEventListener('click',function(event){
var button=event.target.closest?event.target.closest('.themepick button'):null;
if(!button){return;}
apply(button.dataset.theme);
try{localStorage.setItem(KEY,button.dataset.theme);}catch(e){}});
})();
