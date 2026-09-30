// The Sensors page from 840 px: the list at the left chooses which pane the right shows, in
// place, the way the settings menu opens a section; the address follows so a reload lands on
// the same one. Below 840 px the markup is left as served: a row's link opens the item's own
// page, as on a phone.
(function(){
var wide=window.matchMedia('(min-width:840px)');
var ld=document.querySelector('.ld');
if(!ld){return;}
function select(id){
Array.prototype.forEach.call(ld.querySelectorAll('.detail>.pane'),function(pane){pane.hidden=pane.dataset.id!==id;});
Array.prototype.forEach.call(ld.querySelectorAll('.nav li[data-id]'),function(li){li.classList.toggle('on',li.dataset.id===id);});
if(history.replaceState){history.replaceState(null,'',id?'/sensors?id='+encodeURIComponent(id):'/sensors');}
}
ld.addEventListener('click',function(e){
if(!wide.matches){return;}
if(e.target.closest('input,button,label,select')){return;}
var li=e.target.closest('.nav li[data-id]');
if(li){e.preventDefault();select(li.dataset.id);}
});
})();
