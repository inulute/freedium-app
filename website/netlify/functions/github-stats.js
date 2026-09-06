// Netlify Function with in-memory caching
// Cache persists as long as the function stays warm (Netlify keeps functions warm for ~10 minutes)

const CACHE_DURATION = 60 * 60 * 1000; // 1 hour
const GITHUB_USER = 'inulute';
const GITHUB_REPO = 'freedium-app';

// Global cache (persists across invocations while function is warm)
let cache = {
  data: null,
  timestamp: null
};

exports.handler = async (event, context) => {
  // Only allow GET requests
  if (event.httpMethod !== 'GET') {
    return {
      statusCode: 405,
      body: JSON.stringify({ error: 'Method not allowed' })
    };
  }

  try {
    const now = Date.now();
    
    // Check if cache is valid (less than 1 hour old)
    if (cache.data && cache.timestamp) {
      const cacheAge = now - cache.timestamp;
      if (cacheAge < CACHE_DURATION) {
        return {
          statusCode: 200,
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'public, max-age=3600' // CDN cache for 1 hour
          },
          body: JSON.stringify(cache.data)
        };
      }
    }
    
    // Fetch fresh data from GitHub API
    const releasesResponse = await fetch(
      `https://api.github.com/repos/${GITHUB_USER}/${GITHUB_REPO}/releases`
    );
    
    if (!releasesResponse.ok) {
      // If fetch fails, return cached data if available (even if expired)
      if (cache.data) {
        return {
          statusCode: 200,
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'public, max-age=300' // Cache for 5 minutes on error
          },
          body: JSON.stringify(cache.data)
        };
      }
      throw new Error(`GitHub API error: ${releasesResponse.status}`);
    }
    
    const releases = await releasesResponse.json();
    let totalDownloads = 0;
    
    releases.forEach(release => {
      if (release.assets && release.assets.length > 0) {
        release.assets.forEach(asset => {
          totalDownloads += asset.download_count || 0;
        });
      }
    });
    
    // Fetch repo info to get star count
    const repoResponse = await fetch(
      `https://api.github.com/repos/${GITHUB_USER}/${GITHUB_REPO}`
    );
    
    if (!repoResponse.ok) {
      // If fetch fails, return cached data if available
      if (cache.data) {
        return {
          statusCode: 200,
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'public, max-age=300'
          },
          body: JSON.stringify(cache.data)
        };
      }
      throw new Error(`GitHub API error: ${repoResponse.status}`);
    }
    
    const repoData = await repoResponse.json();
    const stars = repoData.stargazers_count || 0;
    
    const stats = {
      downloads: totalDownloads,
      stars: stars,
      lastUpdated: new Date().toISOString()
    };
    
    // Update cache
    cache.data = stats;
    cache.timestamp = now;
    
    return {
      statusCode: 200,
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=3600' // CDN cache for 1 hour
      },
      body: JSON.stringify(stats)
    };
    
  } catch (error) {
    console.error('Error fetching GitHub stats:', error);
    
    // Try to return cached data even if expired
    if (cache.data) {
      return {
        statusCode: 200,
        headers: {
          'Content-Type': 'application/json',
          'Cache-Control': 'public, max-age=300'
        },
        body: JSON.stringify(cache.data)
      };
    }
    
    return {
      statusCode: 500,
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ 
        error: 'Failed to fetch stats', 
        downloads: 0, 
        stars: 0 
      })
    };
  }
};




