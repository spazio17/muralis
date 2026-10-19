// The full-screen QR, one view for every QR the web admin shows (the Join code, the
// invitation, the setup QR): as large as the window allows, dark on white in both themes,
// Close at the top start as Material's full-screen dialogs place it. Esc and a click beside
// the code close it, never a click on the code, which someone scanning may brush. Opened by a
// "Show full screen" button (data-qr-full: the id of the image it shows) or the image itself;
// data-qr-left names the element whose text is the time left. No caption: the code, Close and
// the time left are all it needs; the dialog takes the image's description as its name. The view closes by itself when its image is hidden or changes on the page (the
// code was used, closed or ran out), and focus goes back to what opened it.
(function(){
var view=null,shown=null,opener=null,timer=0;
function build(){
view=document.createElement('dialog');view.className='qr-full';
view.innerHTML='<button type="button" class="qr-full-close"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7l10 10M17 7L7 17"/></svg>Close</button>'
+'<div class="qr-full-box"><img alt=""><p class="qr-full-left" aria-live="polite"></p></div>';
document.body.appendChild(view);
view.querySelector('.qr-full-close').addEventListener('click',function(){view.close();});
view.addEventListener('click',function(e){if(e.target===view||e.target.classList.contains('qr-full-box')){view.close();}});
view.addEventListener('close',function(){clearInterval(timer);timer=0;shown=null;if(opener){opener.focus();opener=null;}});}
function tick(button){
if(!shown){return;}
var img=document.getElementById(button.getAttribute('data-qr-full'));
if(!img||img.hidden||img.getAttribute('src')!==shown){view.close();return;}
var leftId=button.getAttribute('data-qr-left'),left=view.querySelector('.qr-full-left');
var text=leftId&&document.getElementById(leftId)?document.getElementById(leftId).textContent:'';
if(left.textContent!==text){left.textContent=text;}left.hidden=!text;}
function open(button){
var img=document.getElementById(button.getAttribute('data-qr-full'));
if(!img||img.hidden||!img.getAttribute('src')){return;}
if(!view){build();}
shown=img.getAttribute('src');opener=button;
var big=view.querySelector('img');big.src=shown;big.alt=img.alt;
view.setAttribute('aria-label',img.alt);
tick(button);view.showModal();view.querySelector('.qr-full-close').focus();
clearInterval(timer);timer=setInterval(function(){tick(button);},1000);}
document.addEventListener('click',function(e){
var button=e.target.closest('[data-qr-full]');
if(button){open(button);return;}
var img=e.target.closest('img');
if(img&&img.id){var opens=document.querySelector('[data-qr-full="'+img.id+'"]');if(opens){open(opens);}}});
})();
