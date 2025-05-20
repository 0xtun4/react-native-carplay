/**
 * @param translation screen coordinate offset
 * @param velocity only reported on iOS/CarPlay
 */
export interface PanGestureWithTranslationEvent {
  translation: { x: number; y: number };
  velocity?: { x: number; y: number };
}

/**
 * @param x screen coordinate of the pinch center
 * @param y screen coordinate of the pinch center
 * @param scaleFactor value > 0.0 and < 2.0 when 1.0 means no scaling, > 1.0 zoom in and < 1.0 zoom out, value == 2.0 is a double tap
 * @namespace Android
 */
export interface PinchGestureEvent {
  x: number;
  y: number;
  scaleFactor: number;
}

/**
 * @param x screen coordinate of the press event
 * @param y screen coordinate of the press event
 * @namespace Android
 */
export interface PressEvent {
  x: number;
  y: number;
}
