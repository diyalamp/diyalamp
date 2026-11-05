// Rebuild the macOS icon: swift desktop/icon-source/render.swift
// The approved profile artwork remains pixel-identical at its original bounds.
// Only the empty white margins become a rounded backplate with edge lighting.
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let sourceDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let sourceURL = sourceDirectory.appendingPathComponent("profile-icon.png")
let outputURL = sourceDirectory.deletingLastPathComponent()
    .appendingPathComponent("src/main/resources/app-icon.png")
let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)!
let original = CGImageSourceCreateImageAtIndex(source, 0, nil)!
let size = original.width
precondition(size == 460 && original.height == size, "Expected the approved 460px profile icon")
let colorSpace = CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
let reference = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: colorSpace, bitmapInfo: bitmapInfo)!
reference.interpolationQuality = .none
reference.draw(original, in: CGRect(x: 0, y: 0, width: size, height: size))
let originalPixels = reference.data!.assumingMemoryBound(to: UInt8.self)
var minX = size, minY = size, maxX = -1, maxY = -1
for y in 0..<size {
    for x in 0..<size {
        let offset = (y * size + x) * 4
        if originalPixels[offset] != 255 || originalPixels[offset + 1] != 255
            || originalPixels[offset + 2] != 255 {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }
}
precondition(minX > 70 && minY > 70 && maxX < size - 70 && maxY < size - 70,
    "Artwork must leave the approved empty margins for the backplate")
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: colorSpace, bitmapInfo: bitmapInfo)!
let bounds = CGRect(x: 24, y: 28, width: 412, height: 412)
let backplate = CGPath(roundedRect: bounds, cornerWidth: 88, cornerHeight: 88, transform: nil)

// Restrained outer shadow; transparent corners allow the Dock to show through.
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: -5), blur: 11,
    color: CGColor(gray: 0, alpha: 0.19))
context.addPath(backplate)
context.setFillColor(CGColor(gray: 1, alpha: 1))
context.fillPath()
context.restoreGState()

// The white center stays flat so the existing artwork can be preserved exactly.
context.saveGState()
context.addPath(backplate)
context.clip()
let bottomGradient = CGGradient(colorsSpace: colorSpace,
    colors: [CGColor(red: 0.90, green: 0.90, blue: 0.92, alpha: 1),
             CGColor(gray: 1, alpha: 1)] as CFArray,
    locations: [0, 1])!
context.drawLinearGradient(bottomGradient, start: CGPoint(x: 230, y: 28),
    end: CGPoint(x: 230, y: 96), options: [])
let sideGradient = CGGradient(colorsSpace: colorSpace,
    colors: [CGColor(gray: 0.5, alpha: 0.08), CGColor(gray: 0.5, alpha: 0)] as CFArray,
    locations: [0, 1])!
context.drawLinearGradient(sideGradient, start: CGPoint(x: 24, y: 230),
    end: CGPoint(x: 56, y: 230), options: [])
context.drawLinearGradient(sideGradient, start: CGPoint(x: 436, y: 230),
    end: CGPoint(x: 404, y: 230), options: [])
context.restoreGState()

// A slim highlight and edge seam make the white surface feel less flat.
let edge = CGPath(roundedRect: bounds.insetBy(dx: 0.75, dy: 0.75),
    cornerWidth: 87.25, cornerHeight: 87.25, transform: nil)
context.addPath(edge)
context.setStrokeColor(CGColor(gray: 0.3, alpha: 0.12))
context.setLineWidth(1.5)
context.strokePath()
context.saveGState()
context.clip(to: CGRect(x: 0, y: 230, width: 460, height: 230))
context.addPath(CGPath(roundedRect: bounds.insetBy(dx: 2, dy: 2),
    cornerWidth: 86, cornerHeight: 86, transform: nil))
context.setStrokeColor(CGColor(gray: 1, alpha: 0.95))
context.setLineWidth(2)
context.strokePath()
context.restoreGState()

// Preserve the source's complete colored region, including its antialiasing.
let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
for y in minY...maxY {
    for x in minX...maxX {
        let offset = (y * size + x) * 4
        for channel in 0..<4 { pixels[offset + channel] = originalPixels[offset + channel] }
    }
}
var compared = 0
for y in minY...maxY {
    for x in minX...maxX {
        let offset = (y * size + x) * 4
        for channel in 0..<4 {
            precondition(pixels[offset + channel] == originalPixels[offset + channel])
        }
        compared += 1
    }
}
precondition(pixels[3] == 0, "Icon corners must remain transparent")
let image = context.makeImage()!
let destination = CGImageDestinationCreateWithURL(outputURL as CFURL,
    UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, image, nil)
precondition(CGImageDestinationFinalize(destination), "PNG output failed")
// Check the written file as well as the in-memory composition.
let encodedSource = CGImageSourceCreateWithURL(outputURL as CFURL, nil)!
let encodedImage = CGImageSourceCreateImageAtIndex(encodedSource, 0, nil)!
let decoded = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: colorSpace, bitmapInfo: bitmapInfo)!
decoded.draw(encodedImage, in: CGRect(x: 0, y: 0, width: size, height: size))
let decodedPixels = decoded.data!.assumingMemoryBound(to: UInt8.self)
for y in minY...maxY {
    for x in minX...maxX {
        let offset = (y * size + x) * 4
        for channel in 0..<4 {
            precondition(decodedPixels[offset + channel] == originalPixels[offset + channel],
                "Encoded PNG changed the approved lamp artwork")
        }
    }
}
print("Saved \(outputURL.path)")
print("Artwork bounds: x=\(minX)...\(maxX), y=\(minY)...\(maxY); \(compared) pixels unchanged")
print("Output: \(size)×\(size) RGBA; transparent corners; lamp geometry unchanged")
