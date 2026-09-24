// Light / dark / follow-the-device, remembered in the browser rather than on the tablet. The
// segmented picker shows on a wide app bar; a narrow one has a single icon button that cycles
// through the same three, since three words do not fit beside the title.
(function(){
var KEY='kiosk-admin-theme',ORDER=['system','light','dark'],NAMES={system:'Automatic',light:'Light',dark:'Dark'};
var current='system';
function apply(mode){
current=mode;
document.documentElement.setAttribute('data-theme',mode);
Array.prototype.forEach.call(document.querySelectorAll('.themepick button'),
function(b){b.setAttribute('aria-pressed',String(b.dataset.theme===mode));});
Array.prototype.forEach.call(document.querySelectorAll('[data-theme-cycle]'),
function(b){b.title='Colour theme: '+NAMES[mode]+'. Tap for '+NAMES[ORDER[(ORDER.indexOf(mode)+1)%3]].toLowerCase();
b.setAttribute('aria-label',b.title);});}
var saved=null;
try{saved=localStorage.getItem(KEY);}catch(e){}
apply(ORDER.indexOf(saved)>=0?saved:'system');
function choose(mode){apply(mode);try{localStorage.setItem(KEY,mode);}catch(e){}}
document.addEventListener('click',function(event){
var cycle=event.target.closest?event.target.closest('[data-theme-cycle]'):null;
if(cycle){choose(ORDER[(ORDER.indexOf(current)+1)%3]);return;}
var button=event.target.closest?event.target.closest('.themepick button'):null;
if(!button){return;}
choose(button.dataset.theme);});
})();
