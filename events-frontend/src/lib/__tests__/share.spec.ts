import { describe, expect, it, vi } from 'vitest'

import { canShareNatively, copyLink, shareLink } from '@/lib/share'

type Share = (data: ShareData) => Promise<void>
type WriteText = (text: string) => Promise<void>

const URL_ = 'https://event-junkie.de/en/events/mock-event'

function navigatorWith(parts: { share?: unknown; writeText?: unknown }): Navigator {
  return {
    share: parts.share,
    clipboard: parts.writeText ? { writeText: parts.writeText } : undefined,
  } as unknown as Navigator
}

describe('canShareNatively', () => {
  it('is true only where navigator.share is a function', () => {
    expect(canShareNatively(navigatorWith({ share: vi.fn<Share>() }))).toBe(true)
    expect(canShareNatively(navigatorWith({}))).toBe(false)
    expect(canShareNatively(undefined)).toBe(false)
  })
})

describe('copyLink', () => {
  it('writes the link to the clipboard', async () => {
    const writeText = vi.fn<WriteText>().mockResolvedValue(undefined)
    expect(await copyLink(URL_, navigatorWith({ writeText }))).toBe('copied')
    expect(writeText).toHaveBeenCalledWith(URL_)
  })

  it('fails where there is no Clipboard API', async () => {
    expect(await copyLink(URL_, navigatorWith({}))).toBe('failed')
  })

  it('fails where the browser refuses the write', async () => {
    const writeText = vi
      .fn<WriteText>()
      .mockRejectedValue(new DOMException('no', 'NotAllowedError'))
    expect(await copyLink(URL_, navigatorWith({ writeText }))).toBe('failed')
  })
})

describe('shareLink', () => {
  it('opens the native share sheet where there is one', async () => {
    const share = vi.fn<Share>().mockResolvedValue(undefined)
    const writeText = vi.fn<WriteText>()
    const outcome = await shareLink(
      { title: 'Mock Fest', url: URL_ },
      navigatorWith({ share, writeText }),
    )
    expect(outcome).toBe('shared')
    expect(share).toHaveBeenCalledWith({ title: 'Mock Fest', url: URL_ })
    expect(writeText).not.toHaveBeenCalled()
  })

  it('copies the link where there is no share sheet', async () => {
    const writeText = vi.fn<WriteText>().mockResolvedValue(undefined)
    expect(await shareLink({ url: URL_ }, navigatorWith({ writeText }))).toBe('copied')
    expect(writeText).toHaveBeenCalledWith(URL_)
  })

  it('copies nothing when the visitor closes the sheet', async () => {
    const share = vi.fn<Share>().mockRejectedValue(new DOMException('closed', 'AbortError'))
    const writeText = vi.fn<WriteText>()
    expect(await shareLink({ url: URL_ }, navigatorWith({ share, writeText }))).toBe('cancelled')
    expect(writeText).not.toHaveBeenCalled()
  })

  it('copies the link when the share sheet fails', async () => {
    const share = vi.fn<Share>().mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    const writeText = vi.fn<WriteText>().mockResolvedValue(undefined)
    expect(await shareLink({ url: URL_ }, navigatorWith({ share, writeText }))).toBe('copied')
  })
})
