/** Route original-file readers from stored extension, with a filename fallback for older metadata. */
export function documentKind(file) {
  const ext = String(file?.ext || String(file?.originName || '').split('.').pop() || '').replace(/^\./, '').toLowerCase()
  if (ext === 'pdf') return 'pdf'
  if (['doc', 'docx', 'docm'].includes(ext)) return 'word'
  if (['md', 'markdown'].includes(ext)) return 'markdown'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image'
  if (['mp4', 'webm', 'ogg'].includes(ext)) return 'video'
  if (['mp3', 'wav', 'flac', 'm4a'].includes(ext)) return 'audio'
  return 'other'
}
