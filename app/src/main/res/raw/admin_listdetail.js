// The Sensors, Automations and Cameras pages from 840 px: the list at the left chooses which pane the
// right shows, in place, the way the settings menu opens a section; the address follows so a
// reload lands on the same one. Below 840 px the markup is left as served: a row's link opens
// the item's own page, as on a phone.
(function(){
var wide=window.matchMedia('(min-width:840px)');
var ld=document.querySelector('.ld');
if(!ld){return;}
var title=document.querySelector('header.appbar h1');
// The shell says which page this is: a refused save answers under /api/automations/save.
var automations=ld.dataset.kind==='automations';
// A page may name its own addresses (Cameras: data-base, data-list).
var base=ld.dataset.base||(automations?'/automations?id=':'/sensors?id=');
var listAddress=ld.dataset.list||(automations?'/automation':'/sensors');
function select(id){
Array.prototype.forEach.call(ld.querySelectorAll('.detail>.pane'),function(pane){pane.hidden=pane.dataset.id!==id;});
Array.prototype.forEach.call(ld.querySelectorAll('.nav li[data-id]'),function(li){var on=li.dataset.id===id;li.classList.toggle('on',on);
if(on){li.setAttribute('aria-current','true');}else{li.removeAttribute('aria-current');}});
if(history.replaceState){history.replaceState(null,'',id?base+encodeURIComponent(id):listAddress);}
// The page is the list with a detail beside it, so it is named after the list, whichever
// row a bookmark opened it on (review, 2026-10-01).
if(title&&ld.dataset.title){title.textContent=ld.dataset.title;}
}
ld.addEventListener('click',function(e){
if(!wide.matches){return;}
if(e.target.closest('input,button,label,select')){return;}
var add=e.target.closest('.nav a[href="/automation"]');
if(add){e.preventDefault();select('');return;}
// An add link naming its pane (Cameras: a camera or a group).
var pane=e.target.closest('.nav a[data-pane]');
if(pane){e.preventDefault();select(pane.dataset.pane);return;}
var li=e.target.closest('.nav li[data-id]');
if(li){e.preventDefault();select(li.dataset.id);}
});
// A row without a link is chosen with the keyboard too: Enter or Space, as on a button. The
// row's words are the button, not the row, which holds a switch of its own: a control inside a
// control is one a screen reader cannot name (axe, 2026-10-15).
function reachable(){var on=wide.matches;
Array.prototype.forEach.call(ld.querySelectorAll('.nav li[data-id]'),function(li){
if(li.querySelector('a')){return;}
var words=li.querySelector('.text')||li;
if(on){words.setAttribute('tabindex','0');words.setAttribute('role','button');}else{words.removeAttribute('tabindex');words.removeAttribute('role');}});}
ld.addEventListener('keydown',function(e){
if(!wide.matches||(e.key!=='Enter'&&e.key!==' ')){return;}
var li=e.target.closest?e.target.closest('.nav li[data-id]'):null;
if(li&&e.target.getAttribute('role')==='button'){e.preventDefault();select(li.dataset.id);}});
reachable();if(wide.addEventListener){wide.addEventListener('change',reachable);}
if(wide.matches&&title&&ld.dataset.title&&ld.dataset.page==='detail'){title.textContent=ld.dataset.title;}
})();
