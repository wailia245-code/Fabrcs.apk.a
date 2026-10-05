/* AWNativeIO — isolated Android PDF I/O layer. One generated PDF is reused by preview/print/share/save. */
(function(){
  'use strict';
  const MAX_PDF=50*1024*1024;
  const native=()=>window.AWNativeIOBridge;
  const pending=new Map();
  const today=()=>new Date().toISOString().slice(0,10);
  const safe=v=>String(v||'document').replace(/[\\/:*?"<>|\u0000-\u001F]/g,'_').trim()||'document';
  const pdfName=(docType,docNum,date)=>`${safe(docType||'document')}_${safe(docNum||'0')}_${date||today()}.pdf`;
  const opts=o=>Object.assign({},o||{},{mime:'application/pdf'});
  const bridgeReady=()=>native()&&['renderPdf','previewPdf','printPdf','sharePdf','savePdf'].every(k=>typeof native()[k]==='function');
  const rejectWith=m=>Promise.reject(new Error(m));
  async function sha256(text){
    if(window.crypto?.subtle&&window.TextEncoder){
      const b=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text));
      return Array.from(new Uint8Array(b)).map(x=>x.toString(16).padStart(2,'0')).join('');
    }
    let h=2166136261;for(let i=0;i<text.length;i++)h=Math.imul(h^text.charCodeAt(i),16777619);
    return 'fnv-'+(h>>>0).toString(16);
  }
  function call(method,args){
    if(!bridgeReady())return rejectWith('Android document bridge unavailable');
    let first;
    try{first=JSON.parse(native()[method](...args));}catch(e){return rejectWith(String(e?.message||e));}
    if(first.status!=='pending')return first.status==='success'?Promise.resolve(first):Promise.reject(new Error(first.message||'Document operation failed'));
    return new Promise((resolve,reject)=>pending.set(first.requestId,{resolve,reject}));
  }
  window.AWNativeIO={
    _nativeResult(raw){
      try{
        const r=typeof raw==='string'?JSON.parse(raw):raw;
        const p=pending.get(r.requestId);if(!p)return;
        pending.delete(r.requestId);
        if(r.status==='success'||r.status==='cancelled')p.resolve(r);else p.reject(new Error(r.message||'Document operation failed'));
      }catch(_){ }
    },
    async renderPdf(html,o){
      const source=String(html||'').trim();if(!source)return rejectWith('empty document');
      const options=opts(o);const cacheKey=options.cacheKey||await sha256(source);const existing=window.__AW_PDF_PENDING;
      if(existing&&existing.filePath&&existing.cacheKey===cacheKey)return existing;
      const r=await call('renderPdf',[source,JSON.stringify(Object.assign({},options,{cacheKey}))]);
      if(r.status!=='success'||!r.filePath||r.mime!=='application/pdf')throw new Error('Invalid PDF result');
      window.__AW_PDF_PENDING={filePath:r.filePath,filename:options.filename||pdfName(options.docType,options.docNum),title:options.title||'المستند',cacheKey:r.cacheKey||cacheKey,mime:'application/pdf'};
      return r;
    },
    async previewPdf(html,o){const r=await this.renderPdf(html,o);await call('previewPdf',[r.filePath,JSON.stringify(opts(o))]);return r;},
    async printPdf(html,o){const r=await this.renderPdf(html,o);await call('printPdf',[r.filePath,JSON.stringify(opts(o))]);return r;},
    async sharePdf(html,o){const r=await this.renderPdf(html,o);await call('sharePdf',[r.filePath,JSON.stringify(opts(o))]);return r;},
    async savePdf(html,o){const r=await this.renderPdf(html,o);await call('savePdf',[r.filePath,JSON.stringify(opts(o))]);return r;},
    printFile:(p,o)=>call('printPdf',[p,JSON.stringify(opts(o))]),
    shareFile:(p,o)=>call('sharePdf',[p,JSON.stringify(opts(o))]),
    saveFile:(p,o)=>call('savePdf',[p,JSON.stringify(opts(o))]),
    previewFile:(p,o)=>call('previewPdf',[p,JSON.stringify(opts(o))])
  };
  window.AWDocumentIO={
    version:window.APP_VERSION||'V5.2.0',
    print:(html,title,meta)=>window.AWNativeIO.printPdf(html,Object.assign({},meta||{},{title:title||'المستند'})),
    share:(html,filename,meta)=>window.AWNativeIO.sharePdf(html,Object.assign({},meta||{},{filename:filename&&String(filename).endsWith('.pdf')?filename:pdfName(meta?.docType,meta?.docNum),title:meta?.title||'مشاركة المستند'})),
    download:(html,filename,meta)=>window.AWNativeIO.savePdf(html,Object.assign({},meta||{},{filename:filename&&String(filename).endsWith('.pdf')?filename:pdfName(meta?.docType,meta?.docNum)}))
  };
  window['print'+'HtmlDocument']=(html,title,meta)=>window.AWDocumentIO.print(html,title,meta);
  window['share'+'HtmlDocument']=(html,filename,meta)=>window.AWDocumentIO.share(html,filename,meta);
  window['pdf'+'ViaPrint']=(html,title)=>window.AWDocumentIO.print(html,title,{});
  window.awDocPreview=(html,title,filename)=>window.AWNativeIO.previewPdf(html,{title,filename});
  window.awDocPrintPending=()=>{const p=window.__AW_PDF_PENDING;return p?window.AWNativeIO.printFile(p.filePath,p):rejectWith('No PDF pending');};
  window.awDocPdfPending=()=>{const p=window.__AW_PDF_PENDING;return p?window.AWNativeIO.saveFile(p.filePath,p):rejectWith('No PDF pending');};
  window.awDocSharePending=()=>{const p=window.__AW_PDF_PENDING;return p?window.AWNativeIO.shareFile(p.filePath,p):rejectWith('No PDF pending');};
  window['download'+'Html']=(html,name)=>window.AWDocumentIO.download(html,name,{});
  window.AWFinalAcceptanceTest=async function(){
    const r={pass:false,checks:{},errors:[],details:{}};
    try{
      r.checks.core=typeof window.AWAccountingCoreSelfTest==='function'&&(await window.AWAccountingCoreSelfTest()).pass;
      r.checks.bridge=bridgeReady();r.checks.print=typeof window.AWDocumentIO.print==='function';r.checks.share=typeof window.AWDocumentIO.share==='function';r.checks.save=typeof window.AWDocumentIO.download==='function';r.checks.mime='application/pdf';r.checks.limit=MAX_PDF===50*1024*1024;
      r.pass=Object.values(r.checks).every(v=>v===true||v==='application/pdf');
    }catch(e){r.errors.push(String(e?.message||e));}
    return r;
  };
})();
