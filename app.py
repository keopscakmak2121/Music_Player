from flask import Flask, jsonify, request, Response, stream_with_context
import yt_dlp
import os
import uuid
import threading
import logging
import glob
import urllib.parse
import time
import json
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from collections import defaultdict

# --- LOG YAPILANDIRMASI ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s', datefmt='%H:%M:%S')

app = Flask(__name__)

# --- DİZİN YAPILANDIRMASI ---
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DOWNLOAD_DIR = os.path.join(BASE_DIR, "downloads")
META_DIR = os.path.join(BASE_DIR, "meta")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)
os.makedirs(META_DIR, exist_ok=True)

MAX_CONCURRENT_DOWNLOADS = 5
download_semaphore = threading.Semaphore(MAX_CONCURRENT_DOWNLOADS)
active_downloads = {}
download_progress = {}
active_downloads_lock = threading.Lock()

cache = {}
cache_lock = threading.Lock()
CACHE_DURATION = 300 

# --- FFmpeg VE COOKIES ---
FFMPEG_PATH = None
for c in [r'C:\ffmpeg\bin\ffmpeg.exe', r'C:\Program Files\ffmpeg\bin\ffmpeg.exe', 'ffmpeg']:
    import shutil
    p = shutil.which(c) if not os.path.exists(c) else c
    if p: FFMPEG_PATH = p; break

cookies_path = os.path.join(BASE_DIR, 'cookies.txt')
COOKIES_FILE = cookies_path if os.path.exists(cookies_path) else None

# --- YARDIMCILAR ---
def slugify_filename(title):
    replacements = {'ğ':'g','Ğ':'G','ü':'u','Ü':'U','ş':'s','Ş':'S','ı':'i','İ':'I','ö':'o','Ö':'O','ç':'c','Ç':'C'}
    for tr, en in replacements.items(): title = title.replace(tr, en)
    title = unicodedata.normalize('NFKD', title).encode('ascii', 'ignore').decode('ascii')
    safe = "".join([c for c in title if c.isalnum() or c in (' ', '.', '_', '-')]).strip()
    return safe if safe else "downloaded_file"

YDL_BASE_OPTS = {
    'quiet': True, 'no_warnings': True, 'cookiefile': COOKIES_FILE,
    'nocheckcertificate': True, 'extract_flat': True,
    'extractor_args': {'youtube': {'player_client': ['ios', 'android', 'web', 'mweb'], 'skip': ['dash', 'hls']}},
}

def get_from_cache(key):
    with cache_lock:
        if key in cache:
            d, ts = cache[key]
            if time.time() - ts < CACHE_DURATION: return d
    return None

def set_to_cache(key, data):
    with cache_lock: cache[key] = (data, time.time())

# --- ENDPOINTLER ---

@app.route('/search')
def search():
    query = request.args.get('q')
    if not query: return jsonify({'error': 'Arama kelimesi gerekli'}), 400
    
    cache_key = f"search_{query}_{request.args.get('page', 1)}"
    cached = get_from_cache(cache_key)
    if cached: return jsonify(cached)

    try:
        count = max(1, min(int(request.args.get('count', 20)), 50))
        page = max(1, int(request.args.get('page', 1)))
        offset = (page - 1) * count

        def fetch_playlists():
            results = []
            try:
                with yt_dlp.YoutubeDL(YDL_BASE_OPTS) as ydl:
                    p_url = f"https://www.youtube.com/results?search_query={urllib.parse.quote(query)}&sp=EgIQAw%253D%253D"
                    res = ydl.extract_info(p_url, download=False)
                    if res and res.get('entries'):
                        for e in res['entries'][:5]:
                            if e and e.get('id'):
                                results.append({
                                    'id': e['id'], 'title': "[LİSTE] " + (e.get('title') or 'İsimsiz'),
                                    'author': e.get('uploader') or e.get('channel', 'YouTube'),
                                    'duration': e.get('video_count', 0),
                                    'thumbnail': (e.get('thumbnails') or [{}])[0].get('url', ''), 'type': 'playlist'
                                })
            except: pass
            return results

        def fetch_videos():
            results = []
            try:
                with yt_dlp.YoutubeDL(YDL_BASE_OPTS) as ydl:
                    res = ydl.extract_info(f"ytsearch{count * 2}:{query}", download=False)
                    if res and res.get('entries'):
                        for e in res['entries']:
                            if e and e.get('id'):
                                results.append({
                                    'id': e['id'], 'title': e.get('title', 'İsimsiz'),
                                    'author': e.get('uploader', ''), 'duration': e.get('duration', 0),
                                    'thumbnail': f"https://i.ytimg.com/vi/{e['id']}/mqdefault.jpg", 'type': 'video'
                                })
            except: pass
            return results

        # HIZLANDIRMA: Playlist ve Videoları aynı anda (paralel) ara
        with ThreadPoolExecutor(max_workers=2) as executor:
            p_future = executor.submit(fetch_playlists)
            v_future = executor.submit(fetch_videos)
            combined = p_future.result() + v_future.result()

        combined.sort(key=lambda x: x['type'] != 'playlist')
        paginated = combined[offset: offset + count]
        result = {'tracks': paginated, 'page': page, 'has_more': len(combined) > offset + count}
        set_to_cache(cache_key, result)
        return jsonify(result)
    except Exception as e: return jsonify({'error': str(e)}), 500

@app.route('/stream/<video_id>')
def stream(video_id):
    try:
        ydl_opts = {**YDL_BASE_OPTS, 'extract_flat': False, 'format': 'bestaudio/best'}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)
        return jsonify({'url': info.get('url'), 'title': info.get('title', ''), 'duration': info.get('duration', 0)})
    except Exception as e: return jsonify({'error': str(e)}), 500

@app.route('/download/<video_id>')
def download(video_id):
    fmt = request.args.get('format', 'mp3').lower()
    output_path = os.path.join(DOWNLOAD_DIR, str(uuid.uuid4()))
    try:
        ydl_opts = {**YDL_BASE_OPTS, 'extract_flat': False, 'format': 'bestaudio/best', 'outtmpl': output_path + '.%(ext)s'}
        if fmt == 'mp3' and FFMPEG_PATH:
            ydl_opts['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=True)
        final_file = glob.glob(output_path + '.*')[0]
        return Response(open(final_file, 'rb'), headers={'Content-Disposition': f'attachment; filename="{slugify_filename(info["title"])}.{fmt}"'})
    except Exception as e: return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    app.run(host='100.122.252.85', port=5050, threaded=True)
