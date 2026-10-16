// Day and night: light, dark or follow the device, remembered in the browser rather than on the
// panel. The settings page's app bar has the button (themeToggle), showing the mode in force; a
// tap moves to the next. Every page applies the choice.
(function(){
var KEY='kiosk-admin-theme',ORDER=['light','dark','system'],NAMES={light:'Light',dark:'Dark',system:'Follow the device'};
var current='system';
function apply(mode){
current=mode;
document.documentElement.setAttribute('data-theme',mode);
var button=document.getElementById('theme-toggle');
if(button){button.title='Day and night: '+NAMES[mode]+'. Tap for '+NAMES[ORDER[(ORDER.indexOf(mode)+1)%3]].toLowerCase();
button.setAttribute('aria-label',button.title);}}
var saved=null;
try{saved=localStorage.getItem(KEY);}catch(e){}
apply(ORDER.indexOf(saved)>=0?saved:'system');
var button=document.getElementById('theme-toggle');
if(button){button.addEventListener('click',function(){
var mode=ORDER[(ORDER.indexOf(current)+1)%3];apply(mode);try{localStorage.setItem(KEY,mode);}catch(e){}});}
})();
