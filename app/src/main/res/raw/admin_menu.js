// The settings page's sections: one open at a time, the choice remembered in this browser.
//
// Each section is a <details>, so without this file the page is a plain accordion that works
// with no scripting at all. With it, opening one section closes the others, the last open
// section comes back on the next visit, and from 840 px the list of sections on the left drives
// the one shown on the right (Material's list-detail; the stylesheet applies that layout only
// where the html element carries the js class, which the page's head sets before the first
// paint). A message about a refused save arrives with its section already open, and that wins
// over the remembered one, because the reason is what the reader came back for.
(function(){
var KEY='muralis-open-section';
var boxes=Array.prototype.slice.call(document.querySelectorAll('details.box'));
if(!boxes.length){return;}
var items=Array.prototype.slice.call(document.querySelectorAll('.sections .item'));
var wideQuery=window.matchMedia('(min-width:840px)');
function wide(){return wideQuery.matches;}
function mark(id){items.forEach(function(a){a.classList.toggle('on',a.dataset.box===id);});}
function remember(id){try{if(id){localStorage.setItem(KEY,id);}else{localStorage.removeItem(KEY);}}catch(e){}}
// The section meant to be open. Toggle events arrive after the fact and out of step with the
// assignments that caused them, so a handler cannot tell "the script closed this" from "the
// reader closed this" by timing; it can by comparing with what was meant.
var current='';
function open(id){
current=id;
boxes.forEach(function(d){var on=d.id===id;if(d.open!==on){d.open=on;}});
mark(id);}
boxes.forEach(function(d){d.addEventListener('toggle',function(){
if(d.open){if(d.id!==current){open(d.id);remember(d.id);}return;}
if(d.id!==current){return;}
// The reader closed the open section by hand: fine on a phone, where the list is the closed
// rows; on a wide screen the right column would be empty, so the section stays.
if(wide()){d.open=true;}else{current='';mark('');remember('');}});});
items.forEach(function(a){a.addEventListener('click',function(event){
event.preventDefault();open(a.dataset.box);remember(a.dataset.box);});});
var already=boxes.filter(function(d){return d.open;})[0];
if(already){open(already.id);}
else{var saved=null;try{saved=localStorage.getItem(KEY);}catch(e){}
var target=boxes.filter(function(d){return d.id===saved;})[0];
if(target){open(target.id);}else if(wide()){open(boxes[0].id);}}
// Turning a phone, or widening a window, into the two-column layout with nothing open would
// show an empty right column; the first section fills it.
function onWidth(){if(wide()&&!boxes.some(function(d){return d.open;})){open(boxes[0].id);}}
if(wideQuery.addEventListener){wideQuery.addEventListener('change',onWidth);}
else if(wideQuery.addListener){wideQuery.addListener(onWidth);}
})();
