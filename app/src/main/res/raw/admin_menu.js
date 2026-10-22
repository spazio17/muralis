// The settings page's sections: one open at a time, and every visit starts with all closed.
//
// Each section is a <details>, so without this file the page is a plain accordion that works
// with no scripting at all. With it, opening one section closes the others, and from 840 px the
// list of sections on the left drives the one shown on the right (Material's list-detail; the
// stylesheet applies that layout only where the html element carries the js class, which the
// page's head sets before the first paint). Nothing is remembered between visits, as on the
// panel (2026-10-07; the last section came back from storage until then): a page opens
// closed, except where the address names a section, "/#box-<section>" after a save (its form
// posts there) or a page's back arrow, "/?open=<section>" after the certificate choice's reload,
// and a message about a refused save, which arrives with its section open.
// The app bar's overflow menu (Reload dashboard, Restart app, Reboot): opens under its button, closes on
// a choice, a click elsewhere or Escape; the arrows move along it.
(function(){
var button=document.getElementById('commands-button'),menu=document.getElementById('commands-menu');
if(!button||!menu){return;}
function menuItems(){return Array.prototype.slice.call(menu.querySelectorAll('button'));}
function close(back){if(!menu.classList.contains('open')){return;}menu.classList.remove('open');button.setAttribute('aria-expanded','false');if(back){button.focus();}}
menuItems().forEach(function(b){b.setAttribute('role','menuitem');});
button.addEventListener('click',function(event){event.stopPropagation();
if(menu.classList.contains('open')){close(true);return;}
menu.classList.add('open');button.setAttribute('aria-expanded','true');menuItems()[0].focus();});
menu.addEventListener('submit',function(){close(true);});
menu.addEventListener('keydown',function(event){var list=menuItems(),at=list.indexOf(document.activeElement);
if(event.key==='ArrowDown'){event.preventDefault();list[(at+1)%list.length].focus();}
else if(event.key==='ArrowUp'){event.preventDefault();list[(at+list.length-1)%list.length].focus();}
else if(event.key==='Escape'){event.preventDefault();close(true);}
else if(event.key==='Tab'){close(false);}});
document.addEventListener('click',function(event){if(!menu.contains(event.target)){close(false);}});
})();
// "Stop following" on a section the whole fleet shares: this panel stops taking what the whole
// fleet shares, as the Fleet section's switch does, and the page shows its own values again.
Array.prototype.forEach.call(document.querySelectorAll('[data-stop-following]'),function(button){
button.addEventListener('click',function(){button.disabled=true;
fetch('/api/setting',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'section=behaviour&fleet_follows=0'})
.then(function(){location.reload();},function(){button.disabled=false;});});});
(function(){
// Kept in localStorage until 2026-10-07 and read by nothing since: cleared where a browser still
// holds it. Bridging code, to go with the first release after 0.6.4.
try{if(localStorage.getItem('muralis-open-section')!==null){localStorage.removeItem('muralis-open-section');}}catch(e){}
var boxes=Array.prototype.slice.call(document.querySelectorAll('details.box'));
if(!boxes.length){return;}
var items=Array.prototype.slice.call(document.querySelectorAll('.sections .item'));
var wideQuery=window.matchMedia('(min-width:840px)');
function wide(){return wideQuery.matches;}
function mark(id){items.forEach(function(a){a.classList.toggle('on',a.dataset.box===id);});}
// The section meant to be open. Toggle events arrive after the fact and out of step with the
// assignments that caused them, so a handler cannot tell "the script closed this" from "the
// reader closed this" by timing; it can by comparing with what was meant.
var current='';
function open(id){
current=id;
boxes.forEach(function(d){var on=d.id===id;if(d.open!==on){d.open=on;}});
mark(id);}
boxes.forEach(function(d){d.addEventListener('toggle',function(){
if(d.open){if(d.id!==current){open(d.id);}return;}
if(d.id!==current){return;}
// The reader closed the open section by hand: fine on a phone, where the list is the closed
// rows; on a wide screen the right column would be empty, so the section stays.
if(wide()){d.open=true;}else{current='';mark('');}});});
items.forEach(function(a){a.addEventListener('click',function(event){
event.preventDefault();open(a.dataset.box);});});
var already=boxes.filter(function(d){return d.open;})[0];
if(already){open(already.id);}
else{var asked=/[?&]open=([a-z]+)/.exec(location.search);
var named=location.hash.replace(/^#/,'')||(asked?'box-'+asked[1]:'');
var target=boxes.filter(function(d){return d.id===named;})[0];
if(target){open(target.id);}else if(wide()){open(boxes[0].id);}}
// Turning a phone, or widening a window, into the two-column layout with nothing open would
// show an empty right column; the first section fills it.
function onWidth(){if(wide()&&!boxes.some(function(d){return d.open;})){open(boxes[0].id);}}
if(wideQuery.addEventListener){wideQuery.addEventListener('change',onWidth);}
else if(wideQuery.addListener){wideQuery.addListener(onWidth);}
})();
