import json, re, numpy as np, collections, os
D='./hackertube-data/'
OUT='./app/src/main/assets/'
os.makedirs(OUT, exist_ok=True)
vids=[json.loads(l) for l in open(D+'videos.jsonl')]

def norm(t): return re.sub(r'[^a-z0-9]+','',t.lower())
seen={}
kept=[]
for v in vids:  # already best-scored first
    n=norm(v['title'])
    if not n or n in seen: continue
    seen[n]=1
    if not re.fullmatch(r'[A-Za-z0-9_-]{11}', v['vid']): continue
    kept.append(v)
keep_ids={v['vid'] for v in kept}
print('kept',len(kept),'of',len(vids))

out=[]
for v in kept:
    q=v.get('quote') or {}
    out.append({
      'i':v['vid'],'t':v['title'],'c':v['channel'],'s':v['summary'],'w':v['why_hn'],
      'k':v['content_type'],'p':v['primary_topic'],'g':v['tags'][:4],
      'a':[c['id'] for c in v['categories']],
      'q':q.get('text') or '','qi':q.get('hn_comment_id') or 0,'qy':str(q.get('year') or ''),
      'f':v['hn']['first_year'],'l':v['hn']['last_year'],'r':v['hn']['recommenders'],
      'n':1 if v['shelf_life']=='news' else 0,'d':1 if v['shelf_life']=='dated' else 0,
      'x':v['score']})
json.dump(out,open(OUT+'videos.json','w'),ensure_ascii=False,separators=(',',':'))

# taxonomy compact
tx=json.load(open(D+'taxonomy.json'))
tc=[{'id':a['id'],'n':a['name'],'c':[{'id':c['id'],'n':c['name']} for c in a['categories']]} for a in tx['areas']]
json.dump(tc,open(OUT+'taxonomy.json','w'),ensure_ascii=False,separators=(',',':'))

# related: embedding neighbours + co-recommended
emb=np.load(D+'embeddings.npy').astype(np.float32)
ids=json.load(open(D+'embedding_ids.json'))
idx=[i for i,v in enumerate(ids) if v in keep_ids]
sub=emb[idx]; subids=[ids[i] for i in idx]
sims=sub@sub.T
np.fill_diagonal(sims,-1)
nn=np.argsort(-sims,axis=1)[:,:8]
rel={}
for r,v in enumerate(subids):
    rel[v]={'m':[subids[j] for j in nn[r]],'p':[]}
for l in open(D+'related.jsonl'):
    o=json.loads(l)
    if o['vid'] in rel:
        rel[o['vid']]['p']=[x['vid'] for x in o['related'] if x['vid'] in keep_ids][:8]
json.dump(rel,open(OUT+'related.json','w'),separators=(',',':'))

# mentions: up to 4 best comments per video
by=collections.defaultdict(list)
for l in open(D+'mentions.jsonl'):
    m=json.loads(l)
    if m['vid'] in keep_ids: by[m['vid']].append(m)
def clean(t):
    t=t.replace('[VIDEO]','this video').replace('[LINK]','a link')
    t=re.sub(r'\s+',' ',t).strip()
    if len(t)>300:
        cut=t[:300]
        k=max(cut.rfind('. '),cut.rfind('? '),cut.rfind('! '))
        t=(cut[:k+1] if k>150 else cut.rsplit(' ',1)[0])+('' if k>150 else '…')
    return t
rank={'strong_rec':0,'rec':1,'reference':2,'critical':3,'joke':9}
mo={}
for v,ms in by.items():
    good=[m for m in ms if m['stance'] in ('strong_rec','rec')]
    pick=sorted(good,key=lambda m:(rank[m['stance']],-len(m['text'])>-60 ))[:4] if good else \
         [m for m in ms if m['stance'] in ('reference','critical')][:2]
    # prefer meaty comments
    if good: pick=sorted(good,key=lambda m:(rank[m['stance']], -min(len(m['text']),300)))[:4]
    mo[v]=[[m['author'],m['month'],m['stance'],clean(m['text']),str(m['hn_comment_id'])] for m in pick]
json.dump(mo,open(OUT+'mentions.json','w'),ensure_ascii=False,separators=(',',':'))
for f in os.listdir(OUT): print(f, round(os.path.getsize(OUT+f)/1e6,2),'MB')
