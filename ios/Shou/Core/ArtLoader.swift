//  ArtLoader.swift
//  Async cover-art fetch with a small in-memory LRU cache, mirroring ArtLoader.kt.
//  Used by the lock-screen Now Playing artwork and (via async timeline) the widget.

import Foundation
import UIKit

enum ArtLoader {

    private static let cache: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.countLimit = 12          // matches the Android LRU size
        return c
    }()

    /// Fetch (and cache) the cover at `urlString`. Returns nil on any failure or a
    /// blank URL — callers fall back to the gradient placeholder.
    static func image(for urlString: String) async -> UIImage? {
        guard !urlString.isEmpty, let url = URL(string: urlString) else { return nil }
        let key = url as NSURL
        if let hit = cache.object(forKey: key) { return hit }
        do {
            var req = URLRequest(url: url)
            req.timeoutInterval = 8
            let (data, _) = try await URLSession.shared.data(for: req)
            guard let img = UIImage(data: data) else { return nil }
            cache.setObject(img, forKey: key)
            return img
        } catch {
            return nil
        }
    }

    /// Centre-crop to `size` and round the corners — used for the Now Playing artwork
    /// so it reads like the Android MediaStyle thumbnail.
    static func roundedCrop(_ image: UIImage, size: CGFloat, radius: CGFloat) -> UIImage {
        let target = CGSize(width: size, height: size)
        let renderer = UIGraphicsImageRenderer(size: target)
        return renderer.image { _ in
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: target),
                         cornerRadius: radius).addClip()
            let scale = max(target.width / image.size.width, target.height / image.size.height)
            let w = image.size.width * scale, h = image.size.height * scale
            image.draw(in: CGRect(x: (target.width - w) / 2, y: (target.height - h) / 2,
                                  width: w, height: h))
        }
    }
}
