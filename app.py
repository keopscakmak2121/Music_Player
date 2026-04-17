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
from functools import lru_cache
from collections import defaultdict

# --- LOG YAPILANDIRMASI ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(message)s',
    datefmt='%H:%M:%S'
)

app = Flask(__name__)

# --- DİZİN VE YOL YAPILANDIRMASI ---
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
STREAM_CACHE_DURATION = 90

# --- FFmpeg YOLU ---
FFMPEG_PATH = None
ffmpeg_candidates = [
    r'C:\ffmpeg\bin\ffmpeg.exe',
    r'C:\Program Files\ffmpeg\bin\ffmpeg.exe',
    r'ffmpeg.exe',
    '/usr/bin/ffmpeg',
    '/usr/local/bin/ffmpeg',
]
for candidate in ffmpeg_candidates:
    if candidate and os.path.exists(candidate):
        FFMPEG_PATH = candidate
        break
if not FFMPEG_PATH:
    import shutil
    ffmpeg_in_path = shutil.which('ffmpeg')
    if ffmpeg_in_path:
        FFMPEG_PATH = ffmpeg_in_path

# --- COOKIES KONTROLÜ ---
cookies_path = os.path.join(BASE_DIR, 'cookies.txt')
COOKIES_FILE = cookies_path if os.path.exists(cookies_path) else None

import re
VIDEO_ID_RE = re.compile(r'^[A-Za-z0-9_\-]{11}$')

def validate_video_id(video_id):
    return bool(VIDEO_ID_RE.match(video_id or ''))

def slugify_filename(title):
    replacements = {
        'ğ': 'g', 'Ğ': 'G', 'ü': 'u', 'Ü': 'U',
        'ş': 's', 'Ş': 'S', 'ı': 'i', 'İ': 'I',
        'ö': 'o', 'Ö': 'O', 'ç': 'c', 'Ç': 'C',
    }
    for tr, en in replacements.items():
        title = title.replace(tr, en)
    title = unicodedata.normalize('NFKD', title).encode('ascii', 'ignore').decode('ascii')
    safe = "".join([c for c in title if c.isalnum() or c in (' ', '.', '_', '-')]).strip()
    return safe if safe else "downloaded_file"

# --- YT-DLP TEMEL AYARLARI ---
YDL_BASE_OPTS = {
    'quiet': True,
    'no_warnings': True,
    'cookiefile': COOKIES_FILE,
    'user_agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'socket_timeout': 30,
    'retries': 5,
    'extract_flat': True,
    'nocheckcertificate': True,
    'extractor_args': {
        'youtube': {
            'player_client': ['ios', 'android', 'web', 'mweb'],
            'skip': ['dash', 'hls'],
        }
    },
}
if FFMPEG_PATH:
    YDL_BASE_OPTS['ffmpeg_location'] = FFMPEG_PATH

# --- ÖNBELLEK ---
def get_from_cache(key, ttl=None):
    if ttl is None: ttl = CACHE_DURATION
    with cache_lock:
        if key in cache:
            data, ts = cache[key]
            if time.time() - ts < ttl: return data
            del cache[key]
    return None

def set_to_cache(key, data):
    with cache_lock: cache[key] = (data, time.time())

# --- META YARDIMCILARI ---
def meta_path(video_id, ext):
    return os.path.join(META_DIR, f"{video_id}.{ext}.json")

def save_meta(video_id, ext, title, filename):
    data = {'video_id': video_id, 'title': title, 'ext': ext, 'filename': filename, 'downloaded_at': time.time()}
    with open(meta_path(video_id, ext), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)

def load_meta(video_id, ext):
    p = meta_path(video_id, ext)
    if os.path.exists(p):
        with open(p, 'r', encoding='utf-8') as f: return json.load(f)
    return None

def get_downloaded_file(video_id, ext):
    meta = load_meta(video_id, ext)
    if not meta: return None, None
    filepath = os.path.join(DOWNLOAD_DIR, meta['filename'])
    if os.path.exists(filepath): return filepath, meta
    try: os.remove(meta_path(video_id, ext))
    except Exception: pass
    return None, None

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
        combined_results = []
        
        with yt_dlp.YoutubeDL(YDL_BASE_OPTS) as ydl:
            # Playlistleri Ara
            try:
                encoded_q = urllib.parse.quote(query)
                # YouTube playlist filtresi: sp=EgIQAw%253D%253D
                p_url = f"https://www.youtube.com/results?search_query={encoded_q}&sp=EgIQAw%253D%253D"
                p_res = ydl.extract_info(p_url, download=False)
                if p_res and p_res.get('entries'):
                    for e in p_res['entries'][:5]:
                        if e and e.get('id'):
                            combined_results.append({
                                'id': e['id'], 
                                'title': "[LİSTE] " + (e.get('title') or 'İsimsiz Liste'),
                                'author': e.get('uploader') or e.get('channel', 'YouTube'),
                                'duration': e.get('video_count', 0),
                                'thumbnail': (e.get('thumbnails') or [{}])[0].get('url', ''),
                                'type': 'playlist'
                            })
            except Exception as pe:
                logging.error(f"Playlist arama hatası: {pe}")

            # Videoları Ara
            v_res = ydl.extract_info(f"ytsearch{count * 2}:{query}", download=False)
            if v_res and v_res.get('entries'):
                for e in v_res['entries']:
                    if e and e.get('id'):
                        combined_results.append({
                            'id': e['id'], 'title': e.get('title', 'İsimsiz'),
                            'author': e.get('uploader', ''), 'duration': e.get('duration', 0),
                            'thumbnail': f"https://i.ytimg.com/vi/{e['id']}/mqdefault.jpg", 'type': 'video'
                        })
        
        # Playlistleri başa al
        combined_results.sort(key=lambda x: x['type'] != 'playlist')
        
        paginated = combined_results[offset: offset + count]
        result = {'tracks': paginated, 'page': page, 'has_more': len(combined_results) > offset + count}
        set_to_cache(cache_key, result)
        return jsonify(result)
    except Exception as e: return jsonify({'error': str(e)}), 500

@app.route('/playlist/<playlist_id>')
def get_playlist(playlist_id):
    cache_key = f"playlist_{playlist_id}"
    cached = get_from_cache(cache_key)
    if cached: return jsonify(cached)
    try:
        ydl_opts = {**YDL_BASE_OPTS, 'extract_flat': True, 'playlistend': 50}
        url = f"https://www.youtube.com/playlist?list={playlist_id}"
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info or not info.get('entries'):
            return jsonify({'error': 'Liste bulunamadı veya boş'}), 404
        tracks = []
        for e in info.get('entries', [])[:50]:
            if not e or not e.get('id'): continue
            tracks.append({
                'id': e['id'], 'title': e.get('title', 'Bilinmiyor'),
                'author': e.get('uploader', '') or e.get('channel', ''),
                'duration': e.get('duration', 0),
                'thumbnail': f"https://i.ytimg.com/vi/{e['id']}/mqdefault.jpg", 'type': 'video'
            })
        result = {
            'id': playlist_id, 'title': info.get('title', 'Oynatma Listesi'),
            'author': info.get('uploader', 'YouTube'), 'thumbnail': tracks[0]['thumbnail'] if tracks else '',
            'tracks': tracks, 'total_count': len(tracks)
        }
        set_to_cache(cache_key, result)
        return jsonify(result)
    except Exception as e: return jsonify({'error': str(e)}), 500

@app.route('/stream/<video_id>')
def stream(video_id):
    if not validate_video_id(video_id): return jsonify({'error': 'Geçersiz video ID'}), 400
    cache_key = f"stream_{video_id}"
    cached = get_from_cache(cache_key, ttl=STREAM_CACHE_DURATION)
    if cached: return jsonify(cached)
    try:
        ydl_opts = {**YDL_BASE_OPTS, 'extract_flat': False, 'format': 'bestaudio/best'}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)
        url = info.get('url')
        if not url and info.get('formats'):
            audio_fmts = [f for f in info['formats'] if f.get('url') and f.get('vcodec') == 'none']
            if audio_fmts:
                audio_fmts.sort(key=lambda f: f.get('abr') or 0, reverse=True)
                url = audio_fmts[0]['url']
        if not url: return jsonify({'error': 'URL bulunamadı'}), 404
        result = {'url': url, 'title': info.get('title', ''), 'duration': info.get('duration', 0)}
        set_to_cache(cache_key, result)
        return jsonify(result)
    except Exception as e: return jsonify({'error': str(e)}), 500

@app.route('/download/<video_id>', methods=['GET', 'HEAD'])
def download_file(video_id):
    if not validate_video_id(video_id): return jsonify({'error': 'Geçersiz video ID'}), 400
    if request.method == 'HEAD': return Response(headers={'Accept-Ranges': 'bytes'})
    fmt = request.args.get('format', 'mp3').lower()
    download_key = f"{video_id}_{fmt}"
    filepath, meta = get_downloaded_file(video_id, fmt)
    if filepath:
        file_size = os.path.getsize(filepath)
        def gen():
            with open(filepath, 'rb') as f: yield from f
        return Response(gen(), headers={'Content-Disposition': f'attachment; filename="{slugify_filename(meta["title"])}.{fmt}"', 'Content-Type': 'audio/mpeg' if fmt=='mp3' else 'video/mp4', 'Content-Length': str(file_size)})

    with active_downloads_lock:
        if download_key in active_downloads: return jsonify({'error': 'Zaten indiriliyor'}), 409
        active_downloads[download_key] = True
        download_progress[download_key] = {'status': 'downloading', 'percent': 0}

    def progress_hook(d):
        if d['status'] == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate', 0)
            if total > 0:
                with active_downloads_lock:
                    download_progress[download_key]['percent'] = int(d['downloaded_bytes'] / total * 100)

    output_path = os.path.join(DOWNLOAD_DIR, str(uuid.uuid4()))
    try:
        ydl_opts = {
            **YDL_BASE_OPTS, 'extract_flat': False, 'format': 'bestaudio/best' if fmt=='mp3' else 'best[ext=mp4]/best',
            'outtmpl': output_path + '.%(ext)s', 'progress_hooks': [progress_hook],
        }
        if fmt == 'mp3' and FFMPEG_PATH:
            ydl_opts['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
        
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=True)
        
        final_ext = 'mp3' if fmt=='mp3' else 'mp4'
        actual_file = output_path + '.' + ('mp3' if fmt=='mp3' else info.get('ext', 'mp4'))
        if not os.path.exists(actual_file):
            actual_file = glob.glob(output_path + '.*')[0]
        
        title = info.get('title', video_id)
        permanent_name = f"{slugify_filename(title)}_{video_id}.{final_ext}"
        permanent_path = os.path.join(DOWNLOAD_DIR, permanent_name)
        os.rename(actual_file, permanent_path)
        save_meta(video_id, fmt, title, permanent_name)
        
        with active_downloads_lock: download_progress[download_key]['status'] = 'done'
        return Response(open(permanent_path, 'rb'), headers={'Content-Disposition': f'attachment; filename="{permanent_name}"'})
    except Exception as e:
        logging.error(f"Download Error: {e}")
        with active_downloads_lock: active_downloads.pop(download_key, None)
        return jsonify({'error': str(e)}), 500
    finally:
        with active_downloads_lock: active_downloads.pop(download_key, None)

@app.route('/health')
def health():
    return jsonify({'status': 'ok', 'ffmpeg': FFMPEG_PATH is not None, 'downloads': len(glob.glob(os.path.join(META_DIR, '*.json')))})

if __name__ == '__main__':
    app.run(host='100.122.252.85', port=5050, threaded=True, debug=False)
