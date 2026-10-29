// Clock options: every change shows in the preview at once, the page itself in a frame, before
// Save stores it. A style fills the colour boxes with its defaults and names them, shows Digits
// only for the styles that use a font, a swatch fills its box, the arrows move a screen and the
// hidden field keeps the order the form posts.
(function(){
var form=document.getElementById('clock-form'),frame=document.getElementById('clock-frame');
if(!form||!frame){return;}
function value(name){var el=form.elements[name];return el?el.value:'';}
function checked(name){var el=form.querySelector('input[name="'+name+'"]:checked');return el?el.value:'';}
function style(){return form.querySelector('input[name="style"]:checked');}
function options(){
var screens=[];Array.prototype.forEach.call(form.querySelectorAll('.clock-screens li'),function(li){
var on=li.querySelector('input[type=checkbox]');screens.push({key:li.dataset.key,on:!!(on&&on.checked)});});
return {style:checked('style'),background:value('background'),cells:value('cells'),
text:value('text'),accent:value('accent'),dim:Number(value('dim')),album:value('album'),
font:checked('font'),size:checked('size'),position:checked('position'),label:checked('label'),strip:checked('strip'),
seconds:Number(value('seconds'))||30,flash:!!(form.elements.flash&&form.elements.flash.checked),
screens:screens};}
function send(message){if(frame.contentWindow){frame.contentWindow.postMessage(message,location.origin);}}
var WORDS={style:{cells:'Cells',flip:'Flip',digital:'Digital',nixie:'Nixie',minimal:'Minimal'},font:{antonio:'Antonio',oswald:'Oswald',azeret_mono:'Azeret Mono'},
size:{s:'S',m:'M',l:'L',full:'Full'},position:{top:'top',middle:'middle',bottom:'bottom'},label:{tile:'label first',top:'label at the top',bottom:'label at the bottom',off:'no label'},strip:{off:'no date',top:'date at the top',bottom:'date at the bottom'}};
var saved=null;
// Each closed group says its value on its line; "Not saved" shows while the form differs.
function values(){var o=options(),on=o.screens.filter(function(x){return x.on;}).length;
var picked=style(),own=picked&&picked.dataset.colours!==[o.background,o.cells,o.text,o.accent].join('|').replace(/\s/g,'');
var lines={style:(WORDS.style[o.style]||o.style)+(own?' · own colours':''),digits:WORDS.font[o.font]||o.font,
layout:(WORDS.size[o.size]||o.size)+' · '+(WORDS.position[o.position]||o.position)+' · '+(WORDS.label[o.label]||o.label)+' · '+(WORDS.strip[o.strip]||o.strip),
picture:o.album?form.elements.album.selectedOptions[0].text+', '+o.dim+'% dimmed':'None',screens:on+' of '+o.screens.length+' on',
time:o.seconds+' s · flash '+(o.flash?'on':'off')};
Array.prototype.forEach.call(form.querySelectorAll('[data-value]'),function(el){el.textContent=lines[el.dataset.value]||'';});
var now=JSON.stringify(o);if(saved===null){saved=now;}
var mark=document.getElementById('clock-unsaved');if(mark){mark.hidden=now===saved;}}
function preview(){send({options:options()});values();}
function order(){form.elements.screens.value=Array.prototype.map.call(
form.querySelectorAll('.clock-screens li'),function(li){return li.dataset.key;}).join(',');}
function marks(){Array.prototype.forEach.call(form.querySelectorAll('.swatch'),function(b){
b.classList.toggle('on',value(b.dataset.swatch).replace(/\s/g,'')===b.dataset.rgb);});}
form.addEventListener('change',function(e){var el=e.target;
// Nixie's Lamp colour only while its label shows, the lamp being beside it.
var lamp=form.querySelector('[data-colour-block="accent"]');
if(lamp){lamp.hidden=checked('style')==='nixie'&&checked('label')==='off';}
if(el.name==='style'){var c=el.dataset.colours.split('|'),w=el.dataset.words.split('|');
['background','cells','text','accent'].forEach(function(n,i){form.elements[n].value=c[i];
var p=form.querySelector('[data-colour-label="'+n+'"]'),l=form.querySelector('label[for="f-'+n+'"]');
if(p){p.textContent=w[i];}if(l){l.textContent=w[i]+' (r,g,b)';}});
var digits=form.querySelector('details[data-group="digits"]');if(digits){digits.hidden=el.dataset.font!=='true';}
marks();}
preview();});
form.addEventListener('input',function(){marks();preview();});
form.addEventListener('click',function(e){var show=e.target.closest('[data-show]');
if(show){send({show:show.dataset.show});return;}
var swatch=e.target.closest('.swatch');
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
// One group open at a time: opening one closes the others.
Array.prototype.forEach.call(form.querySelectorAll('details.clock-group'),function(d){
d.addEventListener('toggle',function(){if(!d.open){return;}
Array.prototype.forEach.call(form.querySelectorAll('details.clock-group'),function(o){if(o!==d){o.open=false;}});});});
// Leaving with changes not saved asks first; Save itself leaves freely.
var saving=false;form.addEventListener('submit',function(){saving=true;});
window.addEventListener('beforeunload',function(e){var mark=document.getElementById('clock-unsaved');
if(!saving&&mark&&!mark.hidden){e.preventDefault();e.returnValue='';}});
// The draft once the frame's page is up, so a refused save's values show as they were typed.
frame.addEventListener('load',function(){setTimeout(preview,300);});
marks();values();
})();
