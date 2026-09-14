type Point = {x: number; y: number; z: number}
/** Bound tldraw's per-sample streamline lag in screen space, preserving pressure. */
export function sampleForHandoff(points: Point[], pixelsPerPageUnit: number): Point[] {
 if (points.length < 2) return points
 let length = 0
 for (let i=1;i<points.length;i++) length += Math.hypot(points[i].x-points[i-1].x,points[i].y-points[i-1].y)
 const spacing = Math.max(0.3 / Math.max(pixelsPerPageUnit, 0.01), length / 8192)
 const output: Point[] = [points[0]]
 for (let i=1;i<points.length;i++) {
  const a=points[i-1],b=points[i]
  const count=Math.max(1,Math.ceil(Math.hypot(b.x-a.x,b.y-a.y)/spacing))
  for(let n=1;n<=count;n++){const t=n/count;output.push({x:a.x+(b.x-a.x)*t,y:a.y+(b.y-a.y)*t,z:a.z+(b.z-a.z)*t})}
 }
 return output
}
