// Clock options: every change shows in the preview at once, the page itself in a frame, before
// Save stores it. A look fills the colour boxes, a swatch fills its box, the arrows move a screen
// and the hidden field keeps the order the form posts.
(function(){
var form=document.getElementById('clock-form'),frame=document.getElementById('clock-frame');
if(!form||!frame){return;}
function value(name){var el=form.elements[name];return el?el.value:'';}
function checked(name){var el=form.querySelector('input[name="'+name+'"]:checked');return el?el.value:'';}
function look(){return form.querySelector('input[name="look"]:checked');}
function options(){var colours=(look()?look().dataset.colours:'').split('|');
var screens=[];Array.prototype.forEach.call(form.querySelectorAll('.clock-screens li'),function(li){
var on=li.querySelector('input[type=checkbox]');screens.push({key:li.dataset.key,on:!!(on&&on.checked)});});
return {look:checked('look'),background:value('background'),cells:colours[1]||'34,35,38',
text:value('text'),accent:value('accent'),dim:Number(value('dim')),picture:value('picture'),
font:checked('font'),size:checked('size'),position:checked('position'),strip:checked('strip'),
seconds:Number(value('seconds'))||30,flash:!!(form.elements.flash&&form.elements.flash.checked),
screens:screens};}
function send(message){if(frame.contentWindow){frame.contentWindow.postMessage(message,location.origin);}}
function preview(){send({options:options()});}
function order(){form.elements.screens.value=Array.prototype.map.call(
form.querySelectorAll('.clock-screens li'),function(li){return li.dataset.key;}).join(',');}
function marks(){Array.prototype.forEach.call(form.querySelectorAll('.swatch'),function(b){
b.classList.toggle('on',value(b.dataset.swatch).replace(/\s/g,'')===b.dataset.rgb);});}
form.addEventListener('change',function(e){var el=e.target;
if(el.name==='look'){var c=el.dataset.colours.split('|');
form.elements.background.value=c[0];form.elements.text.value=c[2];form.elements.accent.value=c[3];marks();}
preview();});
form.addEventListener('input',function(){marks();preview();});
form.addEventListener('click',function(e){var swatch=e.target.closest('.swatch');
if(swatch){form.elements[swatch.dataset.swatch].value=swatch.dataset.rgb;marks();preview();return;}
var move=e.target.closest('[data-move]');
if(move){var li=move.closest('li'),list=li.parentNode;
if(move.dataset.move==='-1'&&li.previousElementSibling){list.insertBefore(li,li.previousElementSibling);}
else if(move.dataset.move==='1'&&li.nextElementSibling){list.insertBefore(li.nextElementSibling,li);}
order();preview();move.focus();}});
Array.prototype.forEach.call(document.querySelectorAll('[data-clock-step]'),function(b){
b.addEventListener('click',function(){send({step:Number(b.dataset.clockStep)});});});
var flash=document.getElementById('clock-flash');
if(flash){flash.addEventListener('click',function(){send({flash:true});});}
// The draft once the frame's page is up, so a refused save's values show as they were typed.
frame.addEventListener('load',function(){setTimeout(preview,300);});
marks();
})();
