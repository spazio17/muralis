// The Sensors page from 840 px: the list at the left chooses which pane the right shows, in
// place, the way the settings menu opens a section; the address follows so a reload lands on
// the same one. Below 840 px the markup is left as served: a row's link opens the item's own
// page, as on a phone.
(function(){
var wide=window.matchMedia('(min-width:840px)');
var ld=document.querySelector('.ld');
if(!ld){return;}
var title=document.querySelector('header.appbar h1');
function select(id){
Array.prototype.forEach.call(ld.querySelectorAll('.detail>.pane'),function(pane){pane.hidden=pane.dataset.id!==id;});
Array.prototype.forEach.call(ld.querySelectorAll('.nav li[data-id]'),function(li){var on=li.dataset.id===id;li.classList.toggle('on',on);
if(on){li.setAttribute('aria-current','true');}else{li.removeAttribute('aria-current');}});
if(history.replaceState){history.replaceState(null,'',id?'/sensors?id='+encodeURIComponent(id):'/sensors');}
// The page is the list with a detail beside it, so it is named after the list, whichever
// row a bookmark opened it on (review, 2026-10-01).
if(title&&ld.dataset.title){title.textContent=ld.dataset.title;}
}
ld.addEventListener('click',function(e){
if(!wide.matches){return;}
if(e.target.closest('input,button,label,select')){return;}
var li=e.target.closest('.nav li[data-id]');
if(li){e.preventDefault();select(li.dataset.id);}
});
// A row without a link is chosen with the keyboard too: Enter or Space, as on a button.
function reachable(){var on=wide.matches;
Array.prototype.forEach.call(ld.querySelectorAll('.nav li[data-id]'),function(li){
if(li.querySelector('a')){return;}
if(on){li.setAttribute('tabindex','0');li.setAttribute('role','button');}else{li.removeAttribute('tabindex');li.removeAttribute('role');}});}
ld.addEventListener('keydown',function(e){
if(!wide.matches||(e.key!=='Enter'&&e.key!==' ')){return;}
var li=e.target.closest?e.target.closest('.nav li[data-id]'):null;
if(li&&e.target===li){e.preventDefault();select(li.dataset.id);}});
reachable();if(wide.addEventListener){wide.addEventListener('change',reachable);}
if(wide.matches&&title&&ld.dataset.title&&ld.dataset.page==='detail'){title.textContent=ld.dataset.title;}
})();
